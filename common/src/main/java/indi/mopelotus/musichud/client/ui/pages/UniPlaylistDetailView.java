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
import icyllis.modernui.widget.Toast;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveUniItem;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveUniPlaylist;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import indi.mopelotus.musichud.client.ui.components.Modal;
import indi.mopelotus.musichud.client.ui.components.PlatformSelector;
import indi.mopelotus.musichud.client.ui.components.RouterContainer;
import indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public final class UniPlaylistDetailView extends LinearLayout {
    private final ScopedViewTasks tasks = ClientViewTasks.create();
    private final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();
    private TuneWeaveUniPlaylist playlist;
    private final TextView titleView;
    private final TextView descriptionView;
    private final LinearLayout itemsLayout;
    private List<TuneWeaveUniItem> items = List.of();

    public UniPlaylistDetailView(Context context, TuneWeaveUniPlaylist playlist) {
        super(context);
        this.playlist = playlist;
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(24), dp(16), dp(24));

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(action(context, ".button.back", "arrow_left.png", 0,
                v -> { if (RouterContainer.getInstance() != null) RouterContainer.getInstance().popNavigate(); }),
                actionParams());
        titleView = new TextView(context);
        titleView.setText(playlist.name());
        titleView.setTextSize(Theme.TEXT_SIZE_LARGER);
        titleView.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        toolbar.addView(titleView, new LayoutParams(0, WRAP_CONTENT, 1));
        toolbar.addView(action(context, ".button.edit", "settings.png", 0, v -> editMetadata()), actionParams());
        toolbar.addView(action(context, ".button.refresh", "rotate_cw.png", 0, v -> refresh()), actionParams());
        toolbar.addView(action(context, ".button.playAll", "skip_forward_filled.png", 0, v -> playAll()), actionParams());
        toolbar.addView(action(context, ".text.uniPlaylist.addPlatformPlaylists", "list_plus.png", 0,
                v -> showImportDialog()), actionParams());
        toolbar.addView(action(context, ".text.uniPlaylist.add", "audio_lines.png", 0,
                v -> showAddDialog()), actionParams());
        addView(toolbar, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        descriptionView = new TextView(context);
        updateMetadataViews();
        descriptionView.setTextSize(Theme.TEXT_SIZE_NORMAL);
        descriptionView.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        LayoutParams descriptionParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        descriptionParams.setMargins(dp(52), dp(4), 0, dp(16));
        addView(descriptionView, descriptionParams);

        ScrollView scroll = new ScrollView(context);
        itemsLayout = new LinearLayout(context);
        itemsLayout.setOrientation(VERTICAL);
        scroll.addView(itemsLayout, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        addView(scroll, new LayoutParams(MATCH_PARENT, 0, 1));
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { tasks.attach(); refresh(); }
            @Override public void onViewDetachedFromWindow(View v) { tasks.detach(); }
        });
    }

    private void refresh() {
        tasks.<List<TuneWeaveUniItem>>load(progress -> tuneWeave.listUniPlaylistItems(playlist.reference()),
                loaded -> { items = loaded; render(); }, error -> showMessage(error.getMessage()));
    }

    private void render() {
        itemsLayout.removeAllViews();
        if (items.isEmpty()) {
            showMessage(I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.emptyItems"));
            return;
        }
        for (int index = 0; index < items.size(); index++) {
            final int itemIndex = index;
            TuneWeaveUniItem item = items.get(index);
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView number = new TextView(getContext());
            number.setText((index + 1) + ".");
            number.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            row.addView(number, new LayoutParams(dp(32), WRAP_CONTENT));
            LinearLayout text = new LinearLayout(getContext());
            text.setOrientation(VERTICAL);
            TextView title = new TextView(getContext());
            title.setText(item.title());
            title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            title.setTextSize(Theme.TEXT_SIZE_NORMAL);
            text.addView(title, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            TextView meta = new TextView(getContext());
            meta.setText(item.artists().isEmpty() ? item.sourceRef() : String.join(" / ", item.artists()));
            meta.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            meta.setTextSize(Theme.TEXT_SIZE_SMALL);
            text.addView(meta, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            row.addView(text, new LayoutParams(0, WRAP_CONTENT, 1));
            row.addView(action(getContext(), ".button.play", "skip_forward_filled.png", 0,
                    v -> play(item)), actionParams());
            if (itemIndex > 0) row.addView(action(getContext(), ".button.moveUp", "arrow_left.png", 90,
                    v -> move(itemIndex, itemIndex - 1)), actionParams());
            if (itemIndex + 1 < items.size()) row.addView(action(getContext(), ".button.moveDown", "arrow_left.png", -90,
                    v -> move(itemIndex, itemIndex + 1)), actionParams());
            row.addView(action(getContext(), ".button.remove", "trash_2.png", 0,
                    v -> remove(item)), actionParams());
            row.setPadding(dp(8), dp(6), dp(8), dp(6));
            LayoutParams rowParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(4));
            itemsLayout.addView(row, rowParams);
        }
    }

    private ImageButton action(Context context, String key, String iconName, float rotation,
                               View.OnClickListener listener) {
        ImageButton button = new ImageButton(context);
        String label = key.startsWith(".") ? I18n.get(MusicHud.MOD_ID + key) : key;
        button.setTooltipText(label);
        button.setContentDescription(label);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        Image image = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/" + iconName);
        if (image != null) {
            button.setImageDrawable(new InsetDrawable(new ScaledImageDrawable(
                    context.getResources(), image, dp(16), dp(16)), dp(5)));
        }
        button.setRotation(rotation);
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

    private void showImportDialog() {
        UniPlaylistImportDialog.show(getContext(), playlist, this::refresh, this::showToast);
    }

    private void editMetadata() {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        var original = playlist;
        UniPlaylistMetadataDialog.show(getContext(), ".text.uniPlaylist.edit", original.name(), original.description(),
                (name, description) -> {
                    var updated = new java.util.concurrent.atomic.AtomicReference<TuneWeaveUniPlaylist>();
                    tasks.mutate(binding, () -> updated.set(tuneWeave.updateUniPlaylist(original.reference(), name, description)),
                            () -> { playlist = updated.get(); updateMetadataViews(); refresh(); }, error -> showToast(error.getMessage()));
                });
    }

    private void updateMetadataViews() {
        titleView.setText(playlist.name());
        descriptionView.setText(playlist.description().isBlank()
                ? I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.noDescription") : playlist.description());
    }

    private void showToast(String message) {
        ToastUtil.show(Toast.makeText(getContext(), message, Toast.LENGTH_SHORT));
    }

    private void showAddDialog() {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        LinearLayout form = new LinearLayout(getContext());
        form.setOrientation(VERTICAL);
        PlatformSelector platform = new PlatformSelector(getContext(), TuneWeavePlatform.values());
        platform.setSelectedPlatform(tuneWeave.defaultPlatform());
        form.addView(platform, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        Spinner kind = new Spinner(getContext());
        kind.setAdapter(new ArrayAdapter<>(getContext(), new String[]{
                I18n.get(MusicHud.MOD_ID + ".uniPlaylist.item.track"),
                I18n.get(MusicHud.MOD_ID + ".uniPlaylist.item.video")
        }));
        form.addView(kind, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        EditText input = new EditText(getContext(), null, R.attr.editTextOutlinedStyle);
        input.setHint(I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.refHint"));
        input.setSingleLine(true);
        form.addView(input, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        TextView title = new TextView(getContext());
        title.setText(I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.add"));
        new Modal(getContext(), title, form,
                new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.confirm"), (b, modal) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isBlank()) {
                        String prefix = platform.getSelectedPlatform().apiName();
                        String itemKind = kind.getSelectedItemPosition() == 1 ? "video" : "track";
                        modal.dismiss();
                        add(binding, value.startsWith(prefix + ":") ? value : prefix + ":" + value, itemKind);
                    }
                }),
                new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.cancel"), (b, modal) -> modal.dismiss())).show();
    }

    private void add(ScopedViewTasks.Token binding, String reference, String kind) {
        String target = playlist.reference();
        tasks.mutate(binding, () -> tuneWeave.addUniPlaylistItem(target, reference, kind), this::refresh,
                error -> showToast(error.getMessage()));
    }

    private void play(TuneWeaveUniItem item) {
        try {
            MusicService.getInstance().sendPushMusicToQueue(tuneWeave.uniPlaylistItemTrack(item));
        } catch (RuntimeException error) {
            String message = error.getMessage() == null || error.getMessage().isBlank()
                    ? I18n.get(MusicHud.MOD_ID + ".text.musicPushError") : error.getMessage();
            ToastUtil.show(Toast.makeText(getContext(), message, Toast.LENGTH_SHORT));
        }
    }

    private void playAll() {
        for (TuneWeaveUniItem item : items) play(item);
    }

    private void remove(TuneWeaveUniItem item) {
        String target = playlist.reference();
        tasks.mutate(tasks.capture(), () -> tuneWeave.deleteUniPlaylistItem(target, item.id()), this::refresh,
                error -> showToast(error.getMessage()));
    }

    private void move(int from, int to) {
        if (!tasks.canMutate() || from < 0 || to < 0 || from >= items.size() || to >= items.size()) return;
        List<String> ids = new ArrayList<>(items.stream().map(TuneWeaveUniItem::id).toList());
        String id = ids.remove(from); ids.add(to, id);
        String target = playlist.reference();
        tasks.mutate(tasks.capture(), () -> tuneWeave.reorderUniPlaylistItems(target, List.copyOf(ids)), this::refresh,
                error -> showToast(error.getMessage()));
    }

    private void showMessage(String message) {
        itemsLayout.removeAllViews();
        TextView text = new TextView(getContext());
        text.setText(message == null || message.isBlank() ? I18n.get(MusicHud.MOD_ID + ".button.loadingError") : message);
        text.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        itemsLayout.addView(text, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
    }
}
