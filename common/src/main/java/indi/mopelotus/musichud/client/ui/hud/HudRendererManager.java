package indi.mopelotus.musichud.client.ui.hud;

import com.mojang.blaze3d.platform.Window;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.audio.NowPlayingInfo;
import indi.mopelotus.musichud.client.audio.StreamAudioPlayer;
import indi.mopelotus.musichud.client.interfaces.IClientEventService;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.hud.metadata.*;
import indi.mopelotus.musichud.client.ui.hud.renderer.*;
import indi.mopelotus.musichud.client.utils.ui.ColorExtractor;
import indi.mopelotus.musichud.client.utils.PlayerInfoUtil;
import indi.mopelotus.musichud.client.utils.image.ImageTextureData;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.image.PlatformIconUtils;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.language.I18n;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

public class HudRendererManager {
    private static final ClientConfig clientConfig = ClientConfig.getInstance();
    private static volatile HudRendererManager instance;
    @Getter
    private static volatile boolean loaded = false;
    private final BackgroundRenderer BACKGROUND_RENDERER = BackgroundRenderer.getInstance();
    private final AlbumImageRenderer IMAGE_RENDERER = AlbumImageRenderer.getInstance();
    private final PlayerHeadRenderer PLAYER_HEAD_RENDERER = new PlayerHeadRenderer();
    private final PlayingStatusRenderer PLAYING_STATUS_RENDERER = PlayingStatusRenderer.getInstance();
    private final ProgressRenderer PROGRESS_RENDERER = ProgressRenderer.getInstance();
    private final TextRenderer TITLE_RENDERER = new TextRenderer();
    private final PlatformIconRenderer PLATFORM_ICON_RENDERER = new PlatformIconRenderer();
    private final TextRenderer ARTISTS_AND_ALBUM_RENDERER = new TextRenderer();
    private final TextRenderer PLAY_TIME_RENDERER = new TextRenderer();
    private final ScrollingLyricLineRenderer LYRICS_LINE_RENDERER = new ScrollingLyricLineRenderer();
    private final NowPlayingInfo nowPlayingInfo = NowPlayingInfo.getInstance();
    private final DateTimeFormatter LONG_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final DateTimeFormatter SHORT_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("mm:ss");
    private final HudRenderContext hudRenderContext = new HudRenderContext();
    private volatile HudRenderData hudBaseData;
    private volatile HudRenderData imageDisplayData;
    @Setter
    private volatile Layout baseLayout;
    private float contentInterval;
    private volatile String musicDurationString = "";
    private record LyricStyles(ScrollingLyricLineRenderer.Line first, ScrollingLyricLineRenderer.Line second) {}
    private final indi.mopelotus.musichud.client.ui.LatestAsyncLoader<LyricStyles> lyrics = new indi.mopelotus.musichud.client.ui.LatestAsyncLoader<>(
            task -> Minecraft.getInstance().execute(task), Runnable::run, 1);
    private final indi.mopelotus.musichud.client.ui.LatestAsyncLoader<BackgroundData> artwork = new indi.mopelotus.musichud.client.ui.LatestAsyncLoader<>(
            task -> Minecraft.getInstance().execute(task),
            task -> CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS, MusicHud.EXECUTOR).execute(task), 3);
    private volatile boolean closed;
    private Logger logger;
    private int albumImageThumbnailSize = -1;

    protected HudRendererManager() {
        nowPlayingInfo.getLyricLineUpdateListener().add(lyricLine -> {
            if (closed) return;
            Duration totalDuration = nowPlayingInfo.snapshot().duration();
            long delay = lyricLine == null ? 0 : indi.mopelotus.musichud.client.utils.lyrics.LyricTiming.delayMillis(
                    nowPlayingInfo.getPlayedDuration(), lyricLine.getStartTime());
            lyrics.load(() -> CompletableFuture.supplyAsync(() -> {
                String text = lyricLine == null ? "" : lyricLine.getText();
                String translated = lyricLine == null ? "" : lyricLine.getTranslatedText();
                long scrollMillis = -1;
                if (lyricLine != null) {
                    Duration duration = lyricLine.getDuration();
                    if (duration == null && totalDuration != null) duration = totalDuration.minus(lyricLine.getStartTime());
                    if (duration != null) scrollMillis = Math.max(0, (long)(duration.toMillis() * .8));
                }
                return new LyricStyles(
                        new ScrollingLyricLineRenderer.Line(lyricLine, text, Theme.HUD_FADE_COLOR, Theme.HUD_EMPHASIZE_COLOR, scrollMillis),
                        new ScrollingLyricLineRenderer.Line(lyricLine, translated, Theme.HUD_FADE_COLOR, Theme.HUD_FADE_COLOR, scrollMillis));
            }, CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.MILLISECONDS, MusicHud.EXECUTOR)),
                    lines -> LYRICS_LINE_RENDERER.setLines(lines.first(), lines.second()),
                    error -> MusicHud.LOGGER.debug("HUD lyric update failed", error));
        });        PLAYER_HEAD_RENDERER.setPlayerSkinSupplier(() -> {
            PlayerInfo pusherPlayerInfo = nowPlayingInfo.getPusherPlayerInfo();
            return PlayerInfoUtil.getPlayerSkin(pusherPlayerInfo);
        });
        updateLayoutFromConfig();
        refreshStyle();
        reset();
        IClientEventService.getInstance().registerClientPlayerJoin(player -> Minecraft.getInstance().execute(() -> {
            MusicDetail playing = nowPlayingInfo.snapshot().musicDetail();
            if (playing == null || playing == MusicDetail.NONE) reset();
        }));
    }

    public static HudRendererManager getInstance() {
        if (instance == null) {
            synchronized (HudRendererManager.class) {
                if (instance == null) {
                    instance = new HudRendererManager();

                    updateStatus(StreamAudioPlayer.Status.IDLE);
                    StreamAudioPlayer.getInstance().getStatusChangeListener().add(HudRendererManager::updateStatus);
                    MusicHud.getConnectStatusListeners().add((connectStatus) -> HudRendererManager.updateStatus(null));
                    loaded = true;
                }
            }
        }
        return instance;
    }

    public static void shutdown() {
        HudRendererManager manager = instance;
        if (manager == null) return;
        manager.closed = true;
        manager.lyrics.cancel();
        manager.artwork.cancel();
    }

    private static void updateStatus(@Nullable StreamAudioPlayer.Status status) {
        if (instance != null) {
            instance.PLAYING_STATUS_RENDERER.updateStatus(status);
        }
    }

    public synchronized void updateLayoutFromConfig() {
        try {
            Layout layout = new Layout(
                    "Base",
                    clientConfig.getHudOffsetX(),
                    clientConfig.getHudOffsetY(),
                    clientConfig.getHudWidth(),
                    clientConfig.getHudHeight(),
                    clientConfig.getHudCornerRadius(),
                    HorizontalAlign.valueOf(clientConfig.getHudHorizontalPosition()),
                    VerticalAlign.valueOf(clientConfig.getHudVerticalPosition())
            );
            setBaseLayout(layout);
        } catch (Exception e) {
            if (logger == null) {
                logger = MusicHud.getLogger(HudRendererManager.class);
            }
            logger.error("While configure HUD layout from config", e);
        }
    }

    public synchronized void refreshStyle() { refreshStyle(true); }

    private void refreshStyle(boolean refreshImages) {
        try {
            float height = baseLayout.getHeight();
            float halfHeight = height / 2;
            if (baseLayout.getRadius() > halfHeight) {
                baseLayout.setRadius(halfHeight);
            }

            configureBaseRenderer(baseLayout);

            Layout baseLayout = hudBaseData.getLayout();
            float contentPadding = Math.max(height / 10, 3);

            float imageHeightAndWidth = height - 2 * contentPadding;
            float imageRadius = Math.clamp(baseLayout.getRadius() - contentPadding, 0, imageHeightAndWidth / 2f);
            Layout imageLayout = new Layout("Album", contentPadding, contentPadding, imageHeightAndWidth, imageHeightAndWidth, imageRadius);
            imageLayout.setParent(baseLayout);

            configureImageRenderer(imageLayout);

            float contentHeight = height - contentPadding * 2;
            float contentWidth = baseLayout.getWidth() - imageHeightAndWidth - 3 * contentPadding - baseLayout.getRadius() / 3;
            float contentUnit = Math.max(contentHeight / 32f, 1);
            float titleSize = contentUnit * 7;
            boolean showProgress = contentHeight > 14f;

            float progressHeight = showProgress ? contentUnit * 2 : 0;
            float mainContentX = contentPadding + imageHeightAndWidth + contentPadding;
            float progressY = contentPadding + imageHeightAndWidth - progressHeight - 1;
            float progressRadius = progressHeight / 2;
            Layout progressLayout = new Layout("Progress", mainContentX, progressY, contentWidth, progressHeight, progressRadius);
            progressLayout.setParent(baseLayout);

            configureProgressRenderer(progressLayout);

            contentInterval = Math.min(contentUnit * 2.5f, 2f);

            float availableTitleWidth = contentWidth - titleSize - Math.max(4, contentInterval);
            float titleY = showProgress ? contentPadding + 1f : contentPadding + (contentHeight - titleSize) / 2;
            float statusX = Math.max(mainContentX + contentWidth - titleSize, imageHeightAndWidth + contentPadding - titleSize);
            boolean statusVisible = !(availableTitleWidth - 1.25 * titleSize <= 0);
            float headX = statusX - (statusVisible ? titleSize + Math.max(4, contentInterval) : 0);
            float platformIconX = Math.max(mainContentX,
                    headX - titleSize - Math.max(4, contentInterval));
            float maxTitleWidth = Math.max(0, platformIconX - mainContentX - Math.max(4, contentInterval));

            boolean showInfoLine = contentHeight - titleSize > 11f;
            float infoTextSize = showInfoLine ? contentUnit * 5.5f : 0;

            boolean showLyrics = contentHeight - titleSize - infoTextSize > 14f;
            boolean showSubLyrics = contentHeight - titleSize - infoTextSize > 20f;
            float lyricsSize = showLyrics ? showSubLyrics ? contentUnit * 6 : contentUnit * 7 : 0;
            float subLyricsSize = showSubLyrics ? contentUnit * 5 : 0;

            float lyricsY = contentPadding + titleSize + contentInterval;
            float aboveProgressY = progressY - infoTextSize - contentInterval;
            float progressRightX = mainContentX + contentWidth;

            Layout statusLayout = new Layout("Status", statusX, titleY, titleSize, titleSize, 0f);
            statusLayout.setParent(baseLayout);
            PLAYING_STATUS_RENDERER.configure(statusLayout);
            PLAYING_STATUS_RENDERER.setVisibility(statusVisible);

            Layout layout1 = new Layout("PlayerHead", headX, titleY, titleSize, titleSize, 0f);
            layout1.setParent(baseLayout);
            PLAYER_HEAD_RENDERER.configure(layout1);

            Layout titleLayout = Layout.ofTextLayout("Title", mainContentX, titleY, maxTitleWidth, titleSize);
            titleLayout.setParent(baseLayout);
            Layout platformIconLayout = new Layout("PlatformIcon", platformIconX, titleY, titleSize, titleSize, 0f);
            platformIconLayout.setParent(baseLayout);
            PLATFORM_ICON_RENDERER.configure(platformIconLayout);
            TITLE_RENDERER.configure(titleLayout, Theme.EMPHASIZE_TEXT_COLOR, TextRenderer.Position.LEFT);

            float lyricHeight = contentHeight - titleSize - progressHeight - infoTextSize - contentInterval * 2;
            Layout layout = new Layout("MainContent", mainContentX, lyricsY, contentWidth, lyricHeight, 0);
            layout.setParent(baseLayout);
            LYRICS_LINE_RENDERER.setLayout(layout);
            LYRICS_LINE_RENDERER.setLine1Height(lyricsSize);
            LYRICS_LINE_RENDERER.setLine2Height(subLyricsSize);
            LYRICS_LINE_RENDERER.setLineSpacing((int) contentInterval);

            Layout artistAndAlbumLayout = Layout.ofTextLayout("InfoText", mainContentX, aboveProgressY, contentWidth, infoTextSize);
            artistAndAlbumLayout.setParent(baseLayout);
            Layout playTimeLayout = Layout.ofTextLayout("PlayTimeText", progressRightX, aboveProgressY, contentWidth, infoTextSize);
            playTimeLayout.setParent(baseLayout);
            ARTISTS_AND_ALBUM_RENDERER.configure(artistAndAlbumLayout, Theme.HUD_FADE_COLOR, TextRenderer.Position.LEFT);
            PLAY_TIME_RENDERER.configure(playTimeLayout, Theme.HUD_FADE_COLOR, TextRenderer.Position.RIGHT);

            if (refreshImages) refreshThumbnailSize();
        } catch (Exception e) {
            if (logger == null) {
                logger = MusicHud.getLogger(HudRendererManager.class);
            }
            logger.error("While refresh HUD style", e);
        }
    }

    private void refreshThumbnailSize() {
        Window window = Minecraft.getInstance().getWindow();
        //noinspection ConstantValue
        if (window != null) {
            int thumbnailSize = (int) (imageDisplayData.getLayout().getWidth() * window.getGuiScale());
            if (albumImageThumbnailSize != thumbnailSize) {
                albumImageThumbnailSize = thumbnailSize;
                MusicDetail currentlyPlayingMusicDetail = NowPlayingInfo.getInstance().getCurrentlyPlayingMusicDetail();
                if (currentlyPlayingMusicDetail != null && currentlyPlayingMusicDetail != MusicDetail.NONE) {
                    loadAndSwitchAlbumImageWithRetry(currentlyPlayingMusicDetail);
                }
            }
        }
    }

    private void configureProgressRenderer(Layout layout) {
        PROGRESS_RENDERER.setProgressData(new ProgressBarData(
                layout,
                Theme.HUD_PROGRESS_LEFT,
                Theme.HUD_PROGRESS_CURRENT,
                Theme.HUD_PROGRESS_BACKGROUND,
                layout.getHeight() * 6,
                2f,
                0.01f
        ));
    }

    private void configureBaseRenderer(@NotNull Layout layout) {
        BackgroundImages bgImage = getBackgroundImagesOrElse(null);
        if (hudBaseData == null) {
            hudBaseData = new HudRenderData(layout, bgImage);
        } else {
            hudBaseData.setLayout(layout);
        }
        BACKGROUND_RENDERER.configure(hudBaseData);
    }

    private void configureImageRenderer(Layout imageLayout) {
        if (imageDisplayData == null) {
            imageDisplayData = new HudRenderData(imageLayout);
            imageDisplayData.setFallback(hudBaseData);
        } else {
            imageDisplayData.setLayout(imageLayout);
        }
        IMAGE_RENDERER.configure(imageDisplayData);
    }

    private BackgroundImages getBackgroundImagesOrElse(BackgroundImages bgImages) {
        if (hudBaseData != null) {
            BackgroundImages images = hudBaseData.getTransitionableBackground().getCurrent().image();
            if (images != null) {
                bgImages = images;
            }
        }
        return bgImages;
    }

    public void switchMusic(MusicDetail musicDetail) {
        if (closed) return;
        lyrics.cancel();
        artwork.cancel();
        try {
            if (musicDetail == null || musicDetail.equals(MusicDetail.NONE)) {
                reset();
            } else {
                TITLE_RENDERER.setText(musicDetail.getName());
                PLATFORM_ICON_RENDERER.setPlatform(PlatformIconUtils.platform(musicDetail));
                String artists = musicDetail.getArtists().stream()
                        .map(Artist::getName)
                        .reduce((a, b) -> a + " / " + b)
                        .orElse("");
                ARTISTS_AND_ALBUM_RENDERER.setText(artists + " - " + musicDetail.getAlbum().getName());
                LYRICS_LINE_RENDERER.clear();
                loadAndSwitchAlbumImageWithRetry(musicDetail);
            }
        } catch (Exception e) {
            if (logger == null) {
                logger = MusicHud.getLogger(HudRendererManager.class);
            }
            logger.error("While switching music", e);
        }
    }

    private void loadAndSwitchAlbumImageWithRetry(MusicDetail musicDetail) {
        long seconds = Math.max(0, musicDetail.getDurationMillis() / 1000);
        musicDurationString = seconds >= 3600
                ? String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        artwork.load(() -> loadAlbumImage(musicDetail), data -> {
            if (musicDetail == nowPlayingInfo.getCurrentlyPlayingMusicDetail()) hudBaseData.getTransitionableBackground().startTransition(data);
        }, error -> {
            if (musicDetail == nowPlayingInfo.getCurrentlyPlayingMusicDetail()) hudBaseData.getTransitionableBackground().startTransition(BackgroundData.NONE);
        });
    }

    private CompletableFuture<BackgroundData> loadAlbumImage(MusicDetail musicDetail) {
        ImageTextureData[] imageTextures = new ImageTextureData[2];
        return CompletableFuture.allOf(
                        ImageUtils.downloadSquareAsync(musicDetail.getAlbum().getThumbnailPicUrl(albumImageThumbnailSize)).thenAccept(imageTextureData -> {
                            imageTextures[0] = imageTextureData;
                        }),
                        ImageUtils.downloadSquareAsync(musicDetail.getAlbum().getThumbnailPicUrl(240)).thenAccept(imageTextureData -> {
                            imageTextures[1] = imageTextureData;
                        })
                ).thenApply(imageTextureData -> {
                    BackgroundImages backgroundImages = new BackgroundImages(imageTextures[0], 1f);
                    return new BackgroundData(backgroundImages, indi.mopelotus.musichud.client.utils.image.ClientGraphicsResources.RENDER.access(
                            () -> ColorExtractor.extractColors(imageTextures[1].getTexture())));
                });
    }

    public void reset() {
        if (closed) return;
        lyrics.cancel();
        artwork.cancel();
        TITLE_RENDERER.setText(I18n.get(MusicHud.MOD_ID + ".text.idle"));
        PLATFORM_ICON_RENDERER.setPlatform(null);
        ARTISTS_AND_ALBUM_RENDERER.setText("");
        LYRICS_LINE_RENDERER.clear();
        PLAY_TIME_RENDERER.setText("");
        musicDurationString = "";
        var nextData = BackgroundData.NONE;
        hudBaseData.getTransitionableBackground().startTransition(nextData);
    }

    public void renderFrame(GuiGraphics graphics, @Nullable DeltaTracker deltaTracker) {
        renderFrame(graphics, deltaTracker, false);
    }

    /** Renders the live HUD at draft geometry without writing config or issuing resize downloads. */
    public synchronized void renderEditorPreview(GuiGraphics graphics) {
        renderFrame(graphics, null, true);
    }

    public synchronized void renderPreview(GuiGraphics graphics, HudEditorBounds bounds) {
        Layout saved = baseLayout;
        try {
            baseLayout = new Layout("Base", bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                    Math.min(saved.getRadius(), bounds.height() / 2f));
            refreshStyle(false);
            renderFrame(graphics, null, true);
        } finally {
            baseLayout = saved;
            refreshStyle(false);
        }
    }

    private synchronized void renderFrame(GuiGraphics graphics, @Nullable DeltaTracker deltaTracker, boolean preview) {
        if (closed) return;
        try {
            if (!preview && (!clientConfig.getEnable() || !clientConfig.getEnableHud()
                    || Minecraft.getInstance().screen instanceof indi.mopelotus.musichud.client.ui.screen.MusicHudScreen
                    || Minecraft.getInstance().screen instanceof indi.mopelotus.musichud.client.ui.screen.HudLayoutEditorScreen)) {
                return;
            }
            if (!preview && albumImageThumbnailSize <= 0) {
                refreshThumbnailSize();
            }

            NowPlayingInfo nowPlayingInfo = this.nowPlayingInfo;
            MusicDetail musicDetail = nowPlayingInfo.getCurrentlyPlayingMusicDetail();
            if (musicDetail == null || musicDetail.equals(MusicDetail.NONE)) {
                TITLE_RENDERER.setText(I18n.get(MusicHud.MOD_ID + ".text.idle"));//To prevent i18n lazy loading result in wrong text
                if (!preview && clientConfig.getHideHudWhenNotPlaying()) {
                    return;
                }
            }
            hudBaseData.getTransitionableBackground().updateTransition();

            Duration playedDuration = nowPlayingInfo.getPlayedDuration();
            Duration musicDuration = nowPlayingInfo.getMusicDuration();
            if (playedDuration != null && musicDuration != null && !musicDuration.isZero()) {
                DateTimeFormatter formatter = musicDuration.toHoursPart() >= 1 ?
                        LONG_DATE_TIME_FORMATTER :
                        SHORT_DATE_TIME_FORMATTER;
                String playTimeString = formatter.format(
                        LocalTime.MIDNIGHT.plusSeconds(playedDuration.toSeconds())
                ) + " / " + musicDurationString;
                PLAY_TIME_RENDERER.setText(playTimeString);
            }

            hudRenderContext.clearContext();
            hudRenderContext.setGraphics(graphics);

//            PlayerInfo pusherPlayerInfo = nowPlayingInfo.getPusherPlayerInfo();
//            PLAYER_HEAD_RENDERER.setSkinResource(PlayerInfoUtil.getPlayerSkin(pusherPlayerInfo));

            BACKGROUND_RENDERER.render(hudRenderContext);

            IMAGE_RENDERER.render(hudRenderContext);
            PLAYER_HEAD_RENDERER.render(hudRenderContext);
            PLAYING_STATUS_RENDERER.render(hudRenderContext);
            PROGRESS_RENDERER.render(hudRenderContext);

            float progressWidth = PROGRESS_RENDERER.getProgressData().getLayout().getWidth();
            Layout titleLayout = TITLE_RENDERER.getLayout();
            float gap = Math.max(4, contentInterval);
            float headSpace = PLAYER_HEAD_RENDERER.isVisible() ? PLAYER_HEAD_RENDERER.getLayout().getWidth() + gap : 0;
            float statusSpace = PLAYING_STATUS_RENDERER.isVisible() ? PLAYING_STATUS_RENDERER.getLayout().getWidth() + gap : 0;
            float platformSpace = PLATFORM_ICON_RENDERER.isVisible() ? titleLayout.getHeight() + gap : 0;
            float titleWidth = Math.max(0, progressWidth - headSpace - statusSpace - platformSpace);
            titleLayout.setWidth(titleWidth);
            PLATFORM_ICON_RENDERER.getLayout().setX(titleLayout.getX() + titleWidth + gap);

            PLATFORM_ICON_RENDERER.render(hudRenderContext);
            TITLE_RENDERER.render(hudRenderContext);
            LYRICS_LINE_RENDERER.render(hudRenderContext);

            ARTISTS_AND_ALBUM_RENDERER.getLayout().setWidth(Math.max(0, progressWidth - PLAY_TIME_RENDERER.calcDisplayWidth() - gap));
            ARTISTS_AND_ALBUM_RENDERER.render(hudRenderContext);
            PLAY_TIME_RENDERER.render(hudRenderContext);

            hudRenderContext.prepareUniforms();
        } catch (Exception e) {
            if (logger == null) {
                logger = MusicHud.getLogger(HudRendererManager.class);
            }
            logger.error("While rendering HUD frame", e);
        }
    }

    public void preloadAlbumImage(Album album) {
        ImageUtils.downloadSquareAsync(album.getThumbnailPicUrl(albumImageThumbnailSize));
    }
}
