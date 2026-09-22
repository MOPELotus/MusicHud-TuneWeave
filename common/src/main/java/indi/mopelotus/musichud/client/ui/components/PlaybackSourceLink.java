package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.text.SpannableString;
import icyllis.modernui.text.Spanned;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.Toast;
import icyllis.modernui.view.Gravity;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.music.PlaybackSource;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.ui.CallbackGeneration;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import net.minecraft.client.resources.language.I18n;
import java.util.concurrent.CompletableFuture;

/** Upstream compact source button, using the fork's public playback provenance. */
public final class PlaybackSourceLink extends Button {
    private final CallbackGeneration callbacks = new CallbackGeneration();
    private PlaybackSource source = PlaybackSource.NONE;

    public PlaybackSourceLink(Context context) {
        super(context);
        setTextSize(Theme.TEXT_SIZE_NORMAL);
        setTextColor(Theme.SECONDARY_TEXT_COLOR);
        setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        setSingleLine();
        InsetBackgroundFactory.builder().backgroundColor(Theme.GHOST_BUTTON_STATES)
                .padding(new InsetBackgroundFactory.Padding(0, dp(1), 0, dp(1)))
                .cornerRadius(dp(4)).build().applyBackgroundTo(this);
        setVisibility(GONE);
        setOnClickListener(view -> open());
    }

    public void bind(PlaybackSource next) {
        if (next == null) next = PlaybackSource.NONE;
        if (source.equals(next)) return;
        callbacks.next();
        source = next;
        setVisibility(source.name().isBlank() ? GONE : VISIBLE);
        String name = source.kind().equals("private") ? I18n.get(MusicHud.MOD_ID + ".source.private") : source.name();
        String mode = I18n.get(MusicHud.MOD_ID + ".source.mode." + source.mode());
        SpannableString label = new SpannableString("    " + name);
        addIcon(label, 0, source.kind().equals("album") ? "disc_album.png" : "list_music.png");
        addIcon(label, 2, switch (source.mode()) {
            case "RANDOM" -> "shuffle.png";
            case "SEQUENTIAL" -> "repeat.png";
            case "INTELLIGENT" -> "heart_pulse.png";
            default -> "audio_lines.png";
        });
        setText(label);
        setTooltipText(mode + " · " + name);
        setContentDescription(mode + " · " + name);
        setEnabled(source.navigable());
    }

    private void addIcon(SpannableString label, int offset, String filename) {
        var image = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/" + filename);
        if (image != null) label.setSpan(ImageUtils.getIconSpan(image), offset, offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void open() {
        PlaybackSource selected = source;
        if (!selected.navigable()) return;
        long generation = callbacks.next();
        setEnabled(false);
        Object account = MusicEntityCache.captureGeneration();
        var service = TuneWeaveClientService.getInstance();
        CompletableFuture<MusicCollection> future;
        try {
            future = CompletableFuture.supplyAsync(service.prepareRequest(() ->
                    selected.kind().equals("album") ? service.loadAlbumDetail(selected.reference())
                            : service.loadPlaylistDetail(selected.reference())), MusicHud.EXECUTOR);
        } catch (RuntimeException error) {
            future = CompletableFuture.failedFuture(error);
        }
        future.whenComplete((collection, error) -> callbacks.post(MuiModApi::postToUiThread, generation, () -> {
            if (!isAttachedToWindow()) return;
            setEnabled(true);
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
        setEnabled(source.navigable());
        super.onDetachedFromWindow();
    }
}
