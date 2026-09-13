package indi.mopelotus.musichud.client.utils.image;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import icyllis.arc3d.core.ColorSpaces;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Bitmap;
import icyllis.modernui.graphics.BitmapFactory;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.drawable.Drawable;
import icyllis.modernui.graphics.text.FontMetricsInt;
import icyllis.modernui.mc.UIManager;
import icyllis.modernui.text.TextPaint;
import icyllis.modernui.text.style.DynamicDrawableSpan;
import icyllis.modernui.text.style.ImageSpan;
import indi.mopelotus.musichud.MusicHud;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static indi.mopelotus.musichud.MusicHud.getLogger;

public class ImageUtils {
    private static final Logger LOGGER = getLogger(ImageUtils.class);

    private static final int DEFAULT_MAX_CONCURRENT_DOWNLOADS = 40;
    @Getter(AccessLevel.PACKAGE)
    private static final Cache<TextureCacheKey, ImageTextureData> cachedTexturesData = CacheBuilder.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(20))
            .maximumSize(64)
            .build();
    private static final ImageRequests<PendingKey, Object> pendingDownloads = new ImageRequests<>();
    private static final ImageRequests<TextureCacheKey, ImageTextureData> textureDownloads = new ImageRequests<>();
    private static volatile Object cacheEpoch = new Object();
    // 使用虚拟线程的 ExecutorService
    private static ExecutorService downloadExecutor;
    private static Semaphore downloadSemaphore;
    private static int maxConcurrentDownloads = DEFAULT_MAX_CONCURRENT_DOWNLOADS;
    private static final Map<String, Image> cachedIconImageMap = new ConcurrentHashMap<>();

    static {
        initializeVirtualThreadExecutor();
    }

    /**
     * 初始化虚拟线程执行器
     */
    private static void initializeVirtualThreadExecutor() {
        // 使用虚拟线程工厂创建 ExecutorService
        downloadExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("bitmap-download-", 0)
                        .factory()
        );

        // 使用 Semaphore 来限制并发数
        downloadSemaphore = new Semaphore(maxConcurrentDownloads);

        LOGGER.info("Initialized virtual thread executor for bitmap downloads with max concurrent: {}",
                maxConcurrentDownloads);
    }

    /**
     * 设置最大并发下载数
     * 虚拟线程下可以设置更高的并发数(如 50-100)
     *
     * @param maxDownloads 最大并发下载数
     */
    @SuppressWarnings("unused")
    public static void setMaxConcurrentDownloads(int maxDownloads) {
        if (maxDownloads <= 0) {
            throw new IllegalArgumentException("Max concurrent downloads must be positive");
        }

        int oldMax = maxConcurrentDownloads;
        maxConcurrentDownloads = maxDownloads;

        // 重新创建 Semaphore
        int diff = maxDownloads - oldMax;
        if (diff > 0) {
            downloadSemaphore.release(diff);
        } else if (diff < 0) {
            downloadSemaphore.acquireUninterruptibly(-diff);
        }

        LOGGER.info("Updated max concurrent downloads from {} to {}", oldMax, maxDownloads);
    }

    /**
     * 获取当前活跃的下载数
     */
    public static int getActiveDownloads() {
        return maxConcurrentDownloads - downloadSemaphore.availablePermits();
    }

    /**
     * 获取等待中的下载数
     */
    public static int getQueuedDownloads() {
        return downloadSemaphore.getQueueLength();
    }

    /**
     * 异步下载图片
     */
    public static CompletableFuture<ImageTextureData> downloadAsync(String url) {
        return downloadTextureAsync(url, false);
    }

    public static CompletableFuture<ImageTextureData> downloadSquareAsync(String url) {
        return downloadTextureAsync(url, true);
    }

    private static CompletableFuture<ImageTextureData> downloadTextureAsync(String url, boolean squareCrop) {
        Object epoch = cacheEpoch;
        TextureCacheKey cacheKey = new TextureCacheKey(url, squareCrop);
        ImageTextureData cached = cachedTexturesData.getIfPresent(cacheKey);
        if (cached != null) {
            LOGGER.debug("Artwork cache hit");
            return CompletableFuture.completedFuture(cached);
        }
        return textureDownloads.get(cacheKey, () -> downloadAsync(url, inputStream -> {
            var opts = new BitmapFactory.Options();
            opts.inPreferredFormat = Bitmap.Format.RGBA_8888;

            try (Bitmap source = decodeBounded(ImageInputLimits.read(inputStream), opts)) {
                ImageTextureData imageTextureData = squareCrop
                        ? getSquareImageTextureData(url, source)
                        : getImageTextureData(url, source);
                synchronized (ImageUtils.class) {
                    if (epoch == cacheEpoch) cachedTexturesData.put(cacheKey, imageTextureData);
                }
                return imageTextureData;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, false));
    }

    /**
     * 异步下载图片
     *
     * @param url             图片URL
     * @param streamProcessor 输入流处理器
     */
    public static <R> CompletableFuture<R> downloadAsync(String url, Function<InputStream, R> streamProcessor, boolean computable) {
        if (computable) {
            PendingKey key = new PendingKey(url, streamProcessor);
            //noinspection unchecked
            return (CompletableFuture<R>)(CompletableFuture<?>) pendingDownloads.get(key,
                    () -> downloadAsyncInternal(url, streamProcessor).thenApply(value -> (Object)value));
        } else {
            return downloadAsyncInternal(url, streamProcessor);
        }
    }

    private static <R> @NotNull CompletableFuture<R> downloadAsyncInternal(String url, Function<InputStream, R> streamProcessor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                downloadSemaphore.acquire();
                try {
                    LOGGER.debug("Starting artwork download (active: {}, queued: {})", getActiveDownloads(), getQueuedDownloads());

                    R r = downloadImage(url, streamProcessor);
                    LOGGER.debug("Artwork download completed");
                    return r;
                } finally {
                    downloadSemaphore.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException("Download interrupted", e);
            } catch (Exception e) {
                LOGGER.warn("Artwork download failed ({})", e.getClass().getSimpleName());
                throw new CompletionException(e);
            }
        }, downloadExecutor);
    }

    /**
     * 同步下载图片(阻塞当前线程)
     */
    private static <R> R downloadImage(String url, Function<InputStream, R> streamProcessor) throws IOException {
        URI uri = URI.create(url);
        boolean qqCdn = isQqImageHost(uri.getHost());
        var headers = new java.util.LinkedHashMap<String, String>();
        headers.put("User-Agent", "MusicHud TuneWeave/1 (+" + MusicHud.PROJECT_URL + ")");
        headers.put("Accept", qqCdn ? "image/jpeg,image/png;q=0.9" : "image/png,image/jpeg,image/*;q=0.8");
        if (qqCdn) headers.put("Referer", "https://y.qq.com/");
        try (InputStream stream = indi.mopelotus.musichud.client.audio.decoder.PcmAudioInput.openPublicMedia(url, headers)) {
            byte[] bytes = ImageInputLimits.read(stream);
            return streamProcessor.apply(new java.io.ByteArrayInputStream(bytes));
        }
    }

    private static Bitmap decodeBounded(byte[] bytes, BitmapFactory.Options options) throws IOException {
        var info = new BitmapFactory.Options();
        BitmapFactory.decodeByteArrayInfo(bytes, 0, bytes.length, info);
        ImageInputLimits.dimensions(info.outWidth, info.outHeight);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    static boolean isQqImageHost(String host) {
        if (host == null || host.isBlank()) return false;
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return isHostOrSubdomain(normalized, "gtimg.cn")
                || isHostOrSubdomain(normalized, "gtimg.com")
                || isHostOrSubdomain(normalized, "qpic.cn")
                || isHostOrSubdomain(normalized, "qpic.com")
                || isHostOrSubdomain(normalized, "qlogo.cn")
                || isHostOrSubdomain(normalized, "qlogo.com")
                || isHostOrSubdomain(normalized, "qq.com");
    }

    private static boolean isHostOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith('.' + domain);
    }

    public static NativeImage convertBitmapToNativeImage(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        NativeImage nativeImage = new NativeImage(width, height, false);

        try (Bitmap wrap = Bitmap.wrap(
                nativeImage.getPointer(),
                width * 4,
                null,
                width,
                height,
                Bitmap.Format.RGBA_8888,
                false,
                ColorSpaces.SRGB)
        ) {
            wrap.setPixels(bitmap, 0, 0, 0, 0, width, height);
        }
        return nativeImage;
    }

    public static Bitmap convertNativeImageToBitmap(NativeImage nativeImage) {
        int width = nativeImage.getWidth();
        int height = nativeImage.getHeight();

        return Bitmap.wrap(nativeImage.getPointer(),
                width * 4,
                null,
                width,
                height,
                Bitmap.Format.RGBA_8888,
                false,
                ColorSpaces.SRGB);
    }

    @SuppressWarnings("unused")
    public static void cleanup() {
        synchronized (ImageUtils.class) {
            cacheEpoch = new Object();
            cachedTexturesData.invalidateAll();
            pendingDownloads.clear();
            textureDownloads.clear();
        }

        if (downloadExecutor != null && !downloadExecutor.isShutdown()) {
            downloadExecutor.shutdown();
            try {
                if (!downloadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    downloadExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                downloadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        LOGGER.debug("Cleaned up all cached textures and shutdown download executor");
    }

    public static String getCacheStats() {
        return String.format("Cache size: %d, Pending downloads: %d, Active: %d, Queued: %d",
                cachedTexturesData.size(),
                pendingDownloads.size(),
                getActiveDownloads(),
                getQueuedDownloads());
    }

    @SneakyThrows
    public static ImageTextureData loadBase64(String data) {
        byte[] imageBytes = ImageInputLimits.base64(data);
        try (Bitmap source = decodeBounded(imageBytes, new BitmapFactory.Options())) {
            return getImageTextureData(data, source);
        }
    }

    @NotNull
    private static ImageTextureData getImageTextureData(String data, Bitmap source) {
        AtomicReference<DynamicTexture> texture = new AtomicReference<>();
        if (RenderSystem.isOnRenderThread()) {
            texture.set(new DynamicTexture(() -> "image_" + source.hashCode(), convertBitmapToNativeImage(source)));
        } else {
            Minecraft.getInstance().submit(() -> {
                texture.set(new DynamicTexture(() -> "image_" + source.hashCode(), convertBitmapToNativeImage(source)));
            }).join();
        }
        return new ImageTextureData(data, texture.get());
    }

    private static ImageTextureData getSquareImageTextureData(String data, Bitmap source) {
        if (source.getWidth() == source.getHeight()) {
            return getImageTextureData(data, source);
        }
        int side = Math.min(source.getWidth(), source.getHeight());
        int left = (source.getWidth() - side) / 2;
        int top = (source.getHeight() - side) / 2;
        try (Bitmap cropped = source.subImage(left, top, side, side)) {
            return getImageTextureData(data, cropped);
        }
    }

    public static @NotNull ImageSpan getIconSpan(Image image) {
        //noinspection UnstableApiUsage
        Context context = UIManager.getInstance().getDecorView().getContext();
        return new ImageSpan(context, image, DynamicDrawableSpan.ALIGN_CENTER) {
            @Override
            public int getSize(@NonNull TextPaint paint, CharSequence text,
                               int start, int end, @Nullable FontMetricsInt fm) {
                Drawable d = getDrawable();
                int origW = d.getIntrinsicWidth();
                int origH = d.getIntrinsicHeight();
                if (origW <= 0 || origH <= 0) return 0;

                FontMetricsInt pFm = paint.getFontMetricsInt();
                int iconHeight = -pFm.ascent;

                int newWidth = Math.max(1, Math.round((float) iconHeight * origW / origH));

                d.setBounds(0, 0, newWidth, iconHeight);

                if (fm != null) {
                    fm.ascent = -iconHeight;
                    fm.descent = 0;
                }
                return newWidth;
            }
        };
    }

    public static @Nullable Image getImageFromResource(String resourceName) {
        return cachedIconImageMap.computeIfAbsent(resourceName, (s) -> {
            try (InputStream iconResourceStream = MusicHud.class.getResourceAsStream(s)) {
                if (iconResourceStream != null) {
                    return Image.createTextureFromBitmap(BitmapFactory.decodeStream(iconResourceStream));
                } else {
                    return null;
                }
            } catch (Exception ignored) {
                return null;
            }
        });
    }

    record PendingKey(String url, Function<InputStream, ?> consumer) {
    }

    private record TextureCacheKey(String url, boolean squareCrop) {
    }
}
