package indi.mopelotus.musichud.client.ui.pages;

import indi.mopelotus.musichud.client.ui.ScopedViewTasks;
import indi.mopelotus.musichud.client.ui.ClientViewTasks;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.drawable.InsetDrawable;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.ImageButton;
import icyllis.modernui.widget.ImageView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.ArrayAdapter;
import icyllis.modernui.widget.Spinner;
import icyllis.modernui.widget.TextView;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.api.SearchType;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveUniImportSource;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveUniPlaylist;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.Modal;
import indi.mopelotus.musichud.client.ui.components.PlatformSelector;
import indi.mopelotus.musichud.client.ui.components.RouterContainer;
import indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Local TuneWeave Uni Playlist directory and management entry point. */
public final class UniPlaylistView extends LinearLayout {
    private final ScopedViewTasks tasks = ClientViewTasks.create();
    private final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();
    private final LinearLayout list;
    private final TextView progress;

    public UniPlaylistView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(24), dp(16), dp(24));

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(I18n.get(MusicHud.MOD_ID + ".text.page.uniPlaylists"));
        title.setTextSize(Theme.TEXT_SIZE_LARGER);
        title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        toolbar.addView(title, new LayoutParams(0, WRAP_CONTENT, 1));
        toolbar.addView(actionButton(context, "refresh", v -> refresh()), actionParams());
        addView(toolbar, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.RIGHT);
            actions.addView(actionButton(context, "create", v -> showCreateDialog()),
                actionParams());
            actions.addView(actionButton(context, "import", v -> showMultiImportDialog()),
                actionParams());
        actions.addView(actionButton(context, "importDocument", v -> importDocument()), actionParams());
        LayoutParams actionsParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        actionsParams.setMargins(0, dp(8), 0, 0);
        addView(actions, actionsParams);

        TextView hint = new TextView(context);
        hint.setText(I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.description"));
        hint.setTextSize(Theme.TEXT_SIZE_NORMAL);
        hint.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        LayoutParams hintParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        hintParams.setMargins(0, dp(4), 0, dp(16));
        addView(hint, hintParams);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        list = new LinearLayout(context);
        list.setOrientation(VERTICAL);
        scroll.addView(list, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        addView(scroll, new LayoutParams(MATCH_PARENT, 0, 1));
        progress = new TextView(context);
        progress.setTextSize(Theme.TEXT_SIZE_NORMAL);
        progress.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        progress.setVisibility(View.GONE);
        addView(progress, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { tasks.attach(); refresh(); }
            @Override public void onViewDetachedFromWindow(View v) { tasks.detach(); }
        });
    }

    public void refresh() {
        showProgress(I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.loading"));
        tasks.load(progress -> tuneWeave.listUniPlaylists(), this::render, error -> showProgress(error.getMessage()));
    }

    private void render(List<TuneWeaveUniPlaylist> playlists) {
        list.removeAllViews();
        if (playlists.isEmpty()) {
            showProgress(I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.empty"));
            return;
        }
        progress.setVisibility(View.GONE);
        for (TuneWeaveUniPlaylist playlist : playlists) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(ButtonInsetBackgroundFactory.builder().cornerRadius(dp(6)).inset(dp(1)).build().newBackgroundDrawable());
            TextView name = new TextView(getContext());
            name.setText(playlist.name());
            name.setTextSize(Theme.TEXT_SIZE_LARGE);
            name.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            row.addView(name, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            TextView description = new TextView(getContext());
            description.setText(playlist.description().isBlank() ? I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.noDescription") : playlist.description());
            description.setTextSize(Theme.TEXT_SIZE_NORMAL);
            description.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            row.addView(description, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            LinearLayout buttons = new LinearLayout(getContext());
            buttons.setGravity(Gravity.RIGHT);
            buttons.addView(actionButton(getContext(), "open", v -> {
                RouterContainer router = RouterContainer.getInstance();
                if (router != null) router.pushNavigate(new UniPlaylistDetailView(getContext(), playlist));
            }), actionParams());
            buttons.addView(actionButton(getContext(), "edit", v -> showEditDialog(playlist)),
                    actionParams());
            buttons.addView(actionButton(getContext(), "export", v -> export(playlist)), actionParams());
            buttons.addView(actionButton(getContext(), "delete", v -> confirmDelete(playlist)), actionParams());
            row.addView(buttons, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            LayoutParams rowParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(8));
            list.addView(row, rowParams);
        }
    }

    private ImageButton actionButton(Context context, String action, View.OnClickListener listener) {
        String key = switch (action) {
            case "create" -> ".button.create";
            case "import" -> ".button.import";
            case "open" -> ".button.open";
            case "edit" -> ".button.edit";
            case "delete" -> ".button.delete";
            case "export" -> ".button.export";
            case "importDocument" -> ".button.importDocument";
            default -> ".button.refresh";
        };
        String icon = switch (action) {
            case "create" -> "list_plus.png";
            case "import" -> "link.png";
            case "importDocument" -> "unlink.png";
            case "open" -> "arrow_left.png";
            case "edit" -> "settings.png";
            case "export" -> "link.png";
            case "delete" -> "trash_2.png";
            default -> "rotate_cw.png";
        };
        ImageButton button = new ImageButton(context);
        String label = I18n.get(MusicHud.MOD_ID + key);
        button.setTooltipText(label);
        button.setContentDescription(label);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        Image image = ImageUtils.getImageFromResource(
                "/assets/musichud_tuneweave/textures/gui/icons/" + icon);
        if (image != null) {
            button.setImageDrawable(new InsetDrawable(new ScaledImageDrawable(
                    context.getResources(), image, dp(16), dp(16)), dp(5)));
        }
        if ("open".equals(action)) button.setRotation(180);
        button.setBackground(ButtonInsetBackgroundFactory.builder().cornerRadius(dp(4)).inset(dp(1)).build().newBackgroundDrawable());
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

    private void showCreateDialog() {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        UniPlaylistMetadataDialog.show(getContext(), ".text.uniPlaylist.create", "", "",
                (name, description) -> tasks.mutate(binding, () -> tuneWeave.createUniPlaylist(name, description),
                        this::refresh, error -> showProgress(error.getMessage())));
    }

    private void showEditDialog(TuneWeaveUniPlaylist playlist) {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        UniPlaylistMetadataDialog.show(getContext(), ".text.uniPlaylist.edit", playlist.name(), playlist.description(),
                (name, description) -> tasks.mutate(binding, () -> tuneWeave.updateUniPlaylist(playlist.reference(), name, description),
                        this::refresh, error -> showProgress(error.getMessage())));
    }

    private void showMultiImportDialog() {
        UniPlaylistImportDialog.showCreate(getContext(), this::refresh,
                error -> showProgress(error == null ? "" : error));
    }

    private void importDocument() {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        String path = TinyFileDialogs.tinyfd_openFileDialog(
                I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.importDocument"), "", null, "JSON document", false);
        if (path == null || path.isBlank()) return;
        tasks.mutate(binding, () -> {
            try (var stream = Files.newInputStream(Path.of(path))) {
                byte[] bytes = stream.readNBytes(4 * 1024 * 1024 + 1);
                if (bytes.length > 4 * 1024 * 1024) throw new IllegalArgumentException("Uni Playlist document is too large");
                JsonObject document = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                tuneWeave.importUniPlaylistDocument(document);
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        }, this::refresh, error -> showProgress(error.getMessage()));
    }

    private void export(TuneWeaveUniPlaylist playlist) {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        String safeName = playlist.name().replaceAll("[^a-zA-Z0-9._-]+", "_");
        String path = TinyFileDialogs.tinyfd_saveFileDialog(
                I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.exportDocument"),
                safeName.isBlank() ? "uni-playlist.json" : safeName + ".json", null, "JSON document");
        if (path == null || path.isBlank()) return;
        tasks.mutate(binding, () -> {
            JsonObject document = tuneWeave.exportUniPlaylist(playlist.reference());
            try { Files.writeString(Path.of(path), document.toString(), StandardCharsets.UTF_8); }
            catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        }, () -> showProgress(I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.exported")), error -> showProgress(error.getMessage()));
    }

    private void confirmDelete(TuneWeaveUniPlaylist playlist) {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        TextView text = new TextView(getContext());
        text.setText(I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.deleteWarning"));
        TextView title = new TextView(getContext());
        title.setText(playlist.name());
        new Modal(getContext(), title, text,
                new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.delete"), (button, dialog) -> {
                    dialog.dismiss();
                    tasks.mutate(binding, () -> tuneWeave.deleteUniPlaylist(playlist.reference()), this::refresh, error -> showProgress(error.getMessage()));
                }),
                new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.cancel"), (button, dialog) -> dialog.dismiss())).show();
    }

    private void showProgress(String message) { progress.setText(message); progress.setVisibility(View.VISIBLE); }
}
