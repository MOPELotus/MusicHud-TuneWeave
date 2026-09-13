package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ImageView;
import icyllis.modernui.view.Gravity;
import indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import icyllis.modernui.widget.Toast;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.music.PlaybackSource;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.ui.CallbackGeneration;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import net.minecraft.client.resources.language.I18n;
import java.util.concurrent.CompletableFuture;

public final class PlaybackSourceLink extends LinearLayout {
    private final CallbackGeneration callbacks = new CallbackGeneration();
    private PlaybackSource source = PlaybackSource.NONE;
    private final UrlImageView cover;
    private final ImageView modeIcon;
    private final Button navigation;

    public PlaybackSourceLink(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        cover = new UrlImageView(context);
        cover.setCornerRadius(dp(6));
        addView(cover, new LayoutParams(dp(36), dp(36)));
        modeIcon = new ImageView(context);
        modeIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        addView(modeIcon, new LayoutParams(dp(28), dp(28)));
        navigation = new Button(context);
        navigation.setMaxLines(2);
        addView(navigation, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        setVisibility(GONE);
        navigation.setOnClickListener(view -> open());
        cover.setOnClickListener(view -> open());
    }

    public void bind(PlaybackSource source) {
        if (this.source.equals(source)) return;
        callbacks.next();
        this.source = source;
        setVisibility(source.name().isBlank() ? GONE : VISIBLE);
        String name = source.kind().equals("private") ? I18n.get(MusicHud.MOD_ID + ".source.private") : source.name();
        String mode = I18n.get(MusicHud.MOD_ID + ".source.mode." + source.mode());
        navigation.setText(mode + " · " + name);
        navigation.setEnabled(source.navigable());
        cover.clear();
        cover.setVisibility(source.imageUrl().isBlank() ? GONE : VISIBLE);
        if (!source.imageUrl().isBlank()) cover.loadUrl(source.imageUrl());
        cover.setTooltipText(name);
        String icon = switch (source.mode()) {
            case "RANDOM" -> "rotate_cw.png";
            case "SEQUENTIAL" -> "list_music.png";
            case "INTELLIGENT" -> "heart_filled.png";
            default -> "audio_lines.png";
        };
        var image = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/" + icon);
        if (image != null) modeIcon.setImageDrawable(new ScaledImageDrawable(getContext().getResources(), image, dp(18), dp(18)));
        modeIcon.setContentDescription(mode);
    }

    private void open() {
        PlaybackSource selected = source;
        if (!selected.navigable()) return;
        long generation = callbacks.next();
        navigation.setEnabled(false);
        Object account = MusicEntityCache.captureGeneration();
        var service = TuneWeaveClientService.getInstance();
        CompletableFuture<MusicCollection> future = CompletableFuture.supplyAsync(service.prepareRequest(() ->
                selected.kind().equals("album") ? service.loadAlbumDetail(selected.reference())
                        : service.loadPlaylistDetail(selected.reference())), MusicHud.EXECUTOR);
        future.whenComplete((collection, error) -> callbacks.post(MuiModApi::postToUiThread, generation, () -> {
            if (!isAttachedToWindow()) return;
            navigation.setEnabled(true);
            if (account != MusicEntityCache.captureGeneration()) return;
            if (error != null) {
                ToastUtil.show(Toast.makeText(getContext(), I18n.get(MusicHud.MOD_ID + ".button.loadingError"), Toast.LENGTH_SHORT));
                return;
            }
            RouterContainer router = RouterContainer.getInstance();
            if (router != null) router.pushNavigate(new MusicCollectionDetailView(getContext(), collection));
        }));
    }

    @Override protected void onDetachedFromWindow() {
        callbacks.next();
        navigation.setEnabled(source.navigable());
        super.onDetachedFromWindow();
    }
}
