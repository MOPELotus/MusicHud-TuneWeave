package indi.mopelotus.musichud.client.ui.pages;

import indi.mopelotus.musichud.client.ui.ScopedViewTasks;
import indi.mopelotus.musichud.client.ui.ClientViewTasks;

import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.drawable.InsetDrawable;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.ImageButton;
import icyllis.modernui.widget.ImageView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveVideo;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveVideoCreator;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveVideoPart;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.Modal;
import indi.mopelotus.musichud.client.ui.components.RouterContainer;
import indi.mopelotus.musichud.client.ui.components.UrlImageView;
import indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** TuneWeave video detail with explicit multi-part audio playback. */
public final class VideoDetailView extends LinearLayout {
    private final ScopedViewTasks tasks = ClientViewTasks.create();
    private final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();
    private final MusicDetail source;
    private final LinearLayout partsLayout;
    private final TextView status;
    private TuneWeaveVideo video;
    private List<TuneWeaveVideoPart> parts = List.of();

    public VideoDetailView(Context context, MusicDetail source) {
        super(context);
        this.source = source;
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(24), dp(16), dp(24));

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(action(".button.back", v -> {
            if (RouterContainer.getInstance() != null) RouterContainer.getInstance().popNavigate();
        }), actionParams());
        TextView title = new TextView(context);
        title.setText(source.getName());
        title.setTextSize(Theme.TEXT_SIZE_LARGER);
        title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        title.setMaxLines(2);
        toolbar.addView(title, new LayoutParams(0, WRAP_CONTENT, 1));
        toolbar.addView(action(".button.refresh", v -> refresh()), actionParams());
        toolbar.addView(action(".button.playAll", v -> playAll()), actionParams());
        addView(toolbar, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        LinearLayout summary = new LinearLayout(context);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        UrlImageView cover = new UrlImageView(context);
        cover.setCornerRadius(dp(8));
        cover.loadUrl(source.getAlbum().getThumbnailPicUrl(dp(80)));
        summary.addView(cover, new LayoutParams(dp(80), dp(80)));
        ScrollView statusScroll = new ScrollView(context);
        statusScroll.setFillViewport(true);
        statusScroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        status = new TextView(context);
        status.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        status.setTextSize(Theme.TEXT_SIZE_NORMAL);
        status.setGravity(Gravity.CENTER);
        status.setTextAlignment(TEXT_ALIGNMENT_CENTER);
        status.setMinimumHeight(dp(128));
        status.setPadding(dp(8), dp(8), dp(8), dp(8));
        statusScroll.addView(status, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        LayoutParams statusParams = new LayoutParams(0, dp(128), 1);
        statusParams.setMargins(dp(16), 0, dp(16), 0);
        summary.addView(statusScroll, statusParams);
        LayoutParams summaryParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        summaryParams.setMargins(0, dp(16), 0, dp(16));
        addView(summary, summaryParams);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        partsLayout = new LinearLayout(context);
        partsLayout.setOrientation(VERTICAL);
        scroll.addView(partsLayout, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        addView(scroll, new LayoutParams(MATCH_PARENT, 0, 1));
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { tasks.attach(); refresh(); }
            @Override public void onViewDetachedFromWindow(View v) { tasks.detach(); }
        });
    }

    private record Content(TuneWeaveVideo video, List<TuneWeaveVideoPart> parts) {}
    private void refresh() {
        boolean refresh = !tasks.failed();
        tasks.<Content>load(progress -> {
            var loaded = tuneWeave.loadVideoDetail(source);
            var parts = tuneWeave.loadVideoParts(source.getSourceRef(), refresh, values -> progress.accept(new Content(loaded, values)));
            return new Content(loaded, parts);
        }, content -> render(content.video(), content.parts()), error -> showStatus(message(error)));
    }

    private void render(TuneWeaveVideo loadedVideo,
                        List<TuneWeaveVideoPart> loadedParts) {
        video = loadedVideo;
        parts = loadedParts;
        String creators = loadedVideo.creators().stream()
                .map(TuneWeaveVideoCreator::name)
                .reduce((left, right) -> left + " / " + right).orElse("");
        status.setText((creators.isEmpty() ? "" : creators + "\n")
                + (loadedVideo.description().isBlank() ? "" : loadedVideo.description() + "\n")
                + I18n.get(MusicHud.MOD_ID + ".text.video.parts").replace("{}", Integer.toString(loadedParts.size())));
        partsLayout.removeAllViews();
        for (TuneWeaveVideoPart part : loadedParts) {
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(ButtonInsetBackgroundFactory.builder().cornerRadius(dp(6)).inset(dp(1))
                    .build().newBackgroundDrawable());
            TextView number = new TextView(getContext());
            number.setText(part.page() + ".");
            number.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            row.addView(number, new LayoutParams(dp(40), WRAP_CONTENT));
            TextView partTitle = new TextView(getContext());
            partTitle.setText(part.title());
            partTitle.setTextSize(Theme.TEXT_SIZE_NORMAL);
            partTitle.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            partTitle.setMaxLines(2);
            row.addView(partTitle, new LayoutParams(0, WRAP_CONTENT, 1));
            TextView duration = new TextView(getContext());
            duration.setText(formatDuration(part.durationMillis()));
            duration.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            row.addView(duration, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            row.addView(action(".button.play", v -> play(part)), actionParams());
            LayoutParams params = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(6));
            partsLayout.addView(row, params);
        }
    }

    private void playAll() {
        if (video == null) return;
        for (TuneWeaveVideoPart part : parts) play(part);
    }

    private void play(TuneWeaveVideoPart part) {
        if (video == null) return;
        MusicService.getInstance().sendPushMusicToQueue(tuneWeave.videoPartTrack(video, part));
    }

    private ImageButton action(String key, View.OnClickListener listener) {
        ImageButton button = new ImageButton(getContext());
        String label = I18n.get(MusicHud.MOD_ID + key);
        String icon = key.endsWith(".back") ? "arrow_left.png"
                : key.endsWith(".refresh") ? "rotate_cw.png" : "skip_forward_filled.png";
        button.setTooltipText(label);
        button.setContentDescription(label);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        Image image = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/" + icon);
        if (image != null) {
            button.setImageDrawable(new InsetDrawable(new ScaledImageDrawable(
                    getContext().getResources(), image, dp(16), dp(16)), dp(5)));
        }
        button.setBackground(ButtonInsetBackgroundFactory.builder().cornerRadius(dp(4)).inset(dp(1))
                .build().newBackgroundDrawable());
        var binding = tasks.capture();
        button.setOnClickListener(view -> {
            if (key.endsWith(".back") || key.endsWith(".refresh")
                    || (binding == null ? tasks.isCurrent() : tasks.isCurrent(binding))) listener.onClick(view);
        });
        return button;
    }

    private LayoutParams actionParams() {
        LayoutParams params = new LayoutParams(dp(32), dp(32));
        params.setMargins(dp(1), 0, dp(1), 0);
        return params;
    }

    private void showStatus(String text) {
        status.setText(text == null ? "" : text);
        status.setVisibility(VISIBLE);
    }

    private static String formatDuration(int millis) {
        int seconds = Math.max(0, millis / 1000);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
