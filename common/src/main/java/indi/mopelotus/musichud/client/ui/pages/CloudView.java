package indi.mopelotus.musichud.client.ui.pages;

import indi.mopelotus.musichud.client.ui.ScopedViewTasks;
import indi.mopelotus.musichud.client.ui.ClientViewTasks;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.LyricInfo;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveCloudLibrary;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveCloudTrack;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.FlexWrapLayout;
import indi.mopelotus.musichud.client.ui.components.Modal;
import indi.mopelotus.musichud.client.ui.components.RouterContainer;
import indi.mopelotus.musichud.client.ui.components.UrlImageView;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Client-mode cloud library, metadata operations and direct upload/download. */
public final class CloudView extends LinearLayout {
    private final ScopedViewTasks tasks = ClientViewTasks.create();
    private final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();
    private final LinearLayout rows;
    private final TextView status;

    public CloudView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(24), dp(16), dp(24));

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(action(".button.back", v -> RouterContainer.getInstance().popNavigate()),
                new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        TextView title = new TextView(context);
        title.setText(I18n.get(MusicHud.MOD_ID + ".text.cloud.title"));
        title.setTextSize(Theme.TEXT_SIZE_LARGER);
        title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        toolbar.addView(title, new LayoutParams(0, WRAP_CONTENT, 1));
        toolbar.addView(action(".button.refresh", v -> refresh()), new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        toolbar.addView(action(".button.import", v -> showImport()), new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        toolbar.addView(action(".button.upload", v -> selectUpload()), new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        addView(toolbar, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        status = new TextView(context);
        status.setTextSize(Theme.TEXT_SIZE_NORMAL);
        status.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        LayoutParams statusParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        statusParams.setMargins(dp(48), dp(4), 0, dp(12));
        addView(status, statusParams);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        scroll.addView(rows, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        addView(scroll, new LayoutParams(MATCH_PARENT, 0, 1));
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { tasks.attach(); refresh(); }
            @Override public void onViewDetachedFromWindow(View v) { tasks.detach(); }
        });
    }

    private void refresh() {
        boolean refresh = !tasks.failed();
        showStatus(I18n.get(MusicHud.MOD_ID + ".text.cloud.loading"));
        tasks.load(progress -> tuneWeave.loadCloudLibrary(refresh, progress), this::render,
                error -> showStatus(message(error)));
    }

    private void render(TuneWeaveCloudLibrary library) {
        rows.removeAllViews();
        String capacity = I18n.get(MusicHud.MOD_ID + ".text.cloud.capacity")
                .replace("{used}", formatBytes(library.storageSize()))
                .replace("{max}", formatBytes(library.storageMaxSize()));
        if (library.total() == 0 && library.tracks().isEmpty()) {
            showStatus(I18n.get(MusicHud.MOD_ID + ".text.cloud.empty") + "  " + capacity);
            return;
        }
        showStatus(I18n.get(MusicHud.MOD_ID + ".text.cloud.count")
                .replace("{}", Long.toString(library.total())) + "  " + capacity);
        for (TuneWeaveCloudTrack track : library.tracks()) {
            rows.addView(createRow(track), rowParams());
        }
    }

    private View createRow(TuneWeaveCloudTrack cloudTrack) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(ButtonInsetBackgroundFactory.builder()
                .cornerRadius(dp(6)).inset(dp(1)).build().newBackgroundDrawable());

        MusicDetail track = cloudTrack.track();
        LinearLayout summary = new LinearLayout(getContext());
        summary.setGravity(Gravity.CENTER_VERTICAL);
        UrlImageView cover = new UrlImageView(getContext());
        cover.setCornerRadius(dp(6));
        cover.loadUrl(track.getAlbum().getThumbnailPicUrl(dp(56)));
        summary.addView(cover, new LayoutParams(dp(56), dp(56)));

        LinearLayout text = new LinearLayout(getContext());
        text.setOrientation(VERTICAL);
        TextView name = new TextView(getContext());
        name.setText(track.getName());
        name.setTextSize(Theme.TEXT_SIZE_LARGE);
        name.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        name.setMaxLines(2);
        text.addView(name, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        TextView artist = new TextView(getContext());
        artist.setText(track.getArtists().stream().map(Artist::getName).collect(Collectors.joining(" / "))
                + (track.getAlbum().getName().isBlank() ? "" : "  -  " + track.getAlbum().getName()));
        artist.setTextSize(Theme.TEXT_SIZE_SMALL);
        artist.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        artist.setMaxLines(1);
        text.addView(artist, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        TextView metadata = new TextView(getContext());
        metadata.setText(cloudMetadata(cloudTrack));
        metadata.setTextSize(Theme.TEXT_SIZE_SMALL);
        metadata.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        metadata.setMaxLines(1);
        text.addView(metadata, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        LayoutParams textParams = new LayoutParams(0, WRAP_CONTENT, 1);
        textParams.setMargins(dp(12), 0, 0, 0);
        summary.addView(text, textParams);
        row.addView(summary, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        FlexWrapLayout actions = new FlexWrapLayout(getContext());
        actions.addView(action(".button.play", v -> MusicService.getInstance().sendPushMusicToQueue(track)));
        actions.addView(action(".button.download", v -> selectDownload(cloudTrack)));
        actions.addView(action(".button.match", v -> showMatch(cloudTrack)));
        actions.addView(action(".button.lyrics", v -> showLyrics(cloudTrack)));
        actions.addView(action(".button.delete", v -> confirmDelete(cloudTrack)));
        LayoutParams actionParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        actionParams.setMargins(dp(68), dp(4), 0, 0);
        row.addView(actions, actionParams);
        return row;
    }

    private void selectUpload() {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        String path = TinyFileDialogs.tinyfd_openFileDialog(
                I18n.get(MusicHud.MOD_ID + ".text.cloud.upload"), "", null, "Audio file", false);
        if (path == null || path.isBlank()) return;
        LinearLayout form = form();
        EditText song = input(".text.cloud.songHint");
        EditText artist = input(".text.cloud.artistHint");
        EditText album = input(".text.cloud.albumHint");
        form.addView(song); form.addView(artist); form.addView(album);
        new Modal(getContext(), title(".text.cloud.upload"), form,
                new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.upload"), (button, modal) -> {
                    modal.dismiss();
                    String songValue = text(song), artistValue = text(artist), albumValue = text(album);
                    mutate(binding, ".text.cloud.uploading", () -> tuneWeave.uploadCloudTrack(
                            Path.of(path), songValue, artistValue, albumValue));
                }), cancel()).show();
    }

    private void selectDownload(TuneWeaveCloudTrack cloudTrack) {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        String fallback = cloudTrack.filename().isBlank() ? cloudTrack.track().getName() + ".audio"
                : cloudTrack.filename();
        String path = TinyFileDialogs.tinyfd_saveFileDialog(
                I18n.get(MusicHud.MOD_ID + ".text.cloud.download"), fallback, null, "Audio file");
        if (path == null || path.isBlank()) return;
        mutate(binding, ".text.cloud.downloading", () -> tuneWeave.downloadCloudTrack(cloudTrack, Path.of(path)));
    }

    private void showImport() {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        LinearLayout form = form();
        EditText song = input(".text.cloud.songHint");
        EditText artist = input(".text.cloud.artistHint");
        EditText album = input(".text.cloud.albumHint");
        EditText md5 = input(".text.cloud.md5Hint");
        EditText source = input(".text.cloud.sourceHint");
        EditText bitrate = input(".text.cloud.bitrateHint");
        EditText size = input(".text.cloud.sizeHint");
        EditText type = input(".text.cloud.typeHint");
        for (EditText value : List.of(song, artist, album, md5, source, bitrate, size, type)) form.addView(value);
        new Modal(getContext(), title(".text.cloud.import"), form,
                new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.import"), (button, modal) -> {
                    try {
                        long bitrateValue = Long.parseLong(text(bitrate));
                        long sizeValue = Long.parseLong(text(size));
                        if (text(song).isBlank() || text(md5).isBlank() || text(type).isBlank()
                                || bitrateValue < 1000 || sizeValue <= 0) {
                            showStatus(I18n.get(MusicHud.MOD_ID + ".text.cloud.invalidFields"));
                            return;
                        }
                        modal.dismiss();
                        String md5Value = text(md5), sourceValue = text(source), typeValue = text(type);
                        String songValue = text(song), artistValue = text(artist), albumValue = text(album);
                        mutate(binding, ".text.cloud.saving", () -> tuneWeave.importCloudTrack(md5Value, sourceValue,
                                bitrateValue, sizeValue, typeValue, songValue, artistValue, albumValue));
                    } catch (NumberFormatException ignored) {
                        showStatus(I18n.get(MusicHud.MOD_ID + ".text.cloud.invalidFields"));
                    }
                }), cancel()).show();
    }

    private void showMatch(TuneWeaveCloudTrack cloudTrack) {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        EditText target = input(".text.cloud.targetHint");
        new Modal(getContext(), title(".text.cloud.match"), target,
                new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.confirm"), (button, modal) -> {
                    modal.dismiss();
                    String targetValue = text(target);
                    mutate(binding, ".text.cloud.saving", () -> tuneWeave.matchCloudTrack(cloudTrack, targetValue));
                }), cancel()).show();
    }

    private void showLyrics(TuneWeaveCloudTrack cloudTrack) {
        tasks.read(() -> {
            var lyrics = tuneWeave.loadCloudLyrics(cloudTrack);
            String advanced = lyrics.getWordByWordLyric().getLyric();
            return advanced.isBlank() ? lyrics.getLyric().getLyric() : advanced;
        }, content -> showTextModal(cloudTrack.track().getName(), content), error -> showStatus(message(error)));
    }

    private void confirmDelete(TuneWeaveCloudTrack cloudTrack) {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        TextView warning = new TextView(getContext());
        warning.setText(I18n.get(MusicHud.MOD_ID + ".text.cloud.deleteWarning"));
        new Modal(getContext(), title(cloudTrack.track().getName()), warning,
                new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.delete"), (button, modal) -> {
                    modal.dismiss();
                    mutate(binding, ".text.cloud.saving", () -> tuneWeave.deleteCloudTrack(cloudTrack));
                }), cancel()).show();
    }

    private void mutate(ScopedViewTasks.Token binding, String pendingKey, Runnable operation) {
        if (tasks.mutate(binding, operation, this::refresh, error -> showStatus(message(error))))
            showStatus(I18n.get(MusicHud.MOD_ID + pendingKey));
    }

    private void showTextModal(String heading, String content) {
        TextView body = new TextView(getContext());
        body.setText(content == null || content.isBlank()
                ? I18n.get(MusicHud.MOD_ID + ".text.cloud.noLyrics") : content);
        ScrollView scroll = new ScrollView(getContext());
        scroll.addView(body, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        new Modal(getContext(), title(heading), scroll,
                new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.confirm"),
                        (button, modal) -> modal.dismiss())).show();
    }

    private Button action(String key, View.OnClickListener listener) {
        Button button = new Button(getContext());
        button.setText(I18n.get(MusicHud.MOD_ID + key));
        button.setTextSize(Theme.TEXT_SIZE_SMALL);
        button.setTextColor(Theme.PRIMARY_COLOR);
        button.setBackground(ButtonInsetBackgroundFactory.builder().cornerRadius(dp(4)).inset(dp(1))
                .build().newBackgroundDrawable());
        var binding = tasks.capture();
        button.setOnClickListener(view -> {
            if (key.endsWith(".back") || key.endsWith(".refresh")
                    || (binding == null ? tasks.isCurrent() : tasks.isCurrent(binding))) listener.onClick(view);
        });
        return button;
    }

    private EditText input(String key) {
        EditText input = new EditText(getContext(), null, R.attr.editTextOutlinedStyle);
        input.setHint(I18n.get(MusicHud.MOD_ID + key));
        input.setSingleLine(true);
        return input;
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(getContext());
        form.setOrientation(VERTICAL);
        return form;
    }

    private TextView title(String keyOrText) {
        TextView title = new TextView(getContext());
        title.setText(keyOrText.startsWith(".") ? I18n.get(MusicHud.MOD_ID + keyOrText) : keyOrText);
        return title;
    }

    private Modal.ActionButton cancel() {
        return new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.cancel"),
                (button, modal) -> modal.dismiss());
    }

    private LayoutParams rowParams() {
        LayoutParams params = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(6));
        return params;
    }

    private void showStatus(String value) {
        status.setText(value == null ? "" : value);
    }

    private static String text(EditText input) { return input.getText().toString().trim(); }

    private static String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String cloudMetadata(TuneWeaveCloudTrack cloudTrack) {
        StringBuilder result = new StringBuilder();
        if (!cloudTrack.filename().isBlank()) result.append(cloudTrack.filename());
        if (cloudTrack.fileSize() > 0) append(result, formatBytes(cloudTrack.fileSize()));
        if (cloudTrack.bitrate() > 0) append(result, (cloudTrack.bitrate() / 1000) + " kbps");
        if (!cloudTrack.matchedTrackReference().isBlank()) append(result, cloudTrack.matchedTrackReference());
        return result.toString();
    }

    private static void append(StringBuilder builder, String value) {
        if (!builder.isEmpty()) builder.append("  ·  ");
        builder.append(value);
    }

    private static String formatBytes(long value) {
        if (value < 0) return "-";
        if (value < 1024) return value + " B";
        double kb = value / 1024d;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KiB", kb);
        double mb = kb / 1024d;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MiB", mb);
        return String.format(Locale.ROOT, "%.1f GiB", mb / 1024d);
    }
}
