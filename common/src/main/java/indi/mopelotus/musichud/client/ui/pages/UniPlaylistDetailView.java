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
import icyllis.modernui.mc.ui.ClampingScrollView;
import icyllis.modernui.view.ViewGroup;
import indi.mopelotus.musichud.client.ui.components.MusicListItem;
import indi.mopelotus.musichud.client.ui.components.UrlImageView;
import indi.mopelotus.musichud.client.ui.components.FlexWrapLayout;
import indi.mopelotus.musichud.client.ui.layouts.VirtualizedListLayout;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;
import java.util.*;
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
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
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
    private final VirtualizedListLayout<TuneWeaveUniItem, UniRow> itemsLayout;
    private final ClampingScrollView scroll;
    private final TextView status;
    private final UrlImageView cover;
    private final Map<String, Long> rowIds = new HashMap<>();
    private long nextRowId = 1;
    private List<TuneWeaveUniItem> items = List.of();

    public UniPlaylistDetailView(Context context, TuneWeaveUniPlaylist playlist) {
        super(context);
        this.playlist = playlist;
        setOrientation(VERTICAL);
        LinearLayout header = new LinearLayout(context);
        LayoutParams headerParams = new LayoutParams(MATCH_PARENT, dp(128));
        headerParams.setMargins(0, dp(24), 0, dp(24));
        addView(header, headerParams);
        ImageButton back = action(context, ".button.back", "arrow_left.png", 0,
                v -> { if (RouterContainer.getInstance() != null) RouterContainer.getInstance().popNavigate(); });
        header.addView(back, new LayoutParams(dp(40), MATCH_PARENT));
        cover = new UrlImageView(context);
        cover.setCornerRadius(dp(8));
        cover.loadUrl(MusicHud.ICON_BASE64);
        header.addView(cover, new LayoutParams(dp(128), dp(128)));
        LinearLayout info = new LinearLayout(context);
        info.setOrientation(VERTICAL);
        LayoutParams infoParams = new LayoutParams(0, MATCH_PARENT, 1);
        infoParams.setMargins(dp(16), 0, 0, 0);
        header.addView(info, infoParams);
        FlexWrapLayout toolbar = new FlexWrapLayout(context);
        toolbar.setAnimationsEnabled(false);
        info.addView(toolbar, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        TextView type = new TextView(context);
        type.setText(I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.name"));
        type.setTextSize(Theme.TEXT_SIZE_LARGE);
        type.setTextColor(Theme.NORMAL_TEXT_COLOR);
        LayoutParams typeParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        typeParams.setMargins(0, 0, dp(12), 0);
        toolbar.addView(type, typeParams);
        toolbar.addView(action(context, ".button.edit", "settings.png", 0, v -> editMetadata()), actionParams());
        toolbar.addView(action(context, ".button.refresh", "rotate_cw.png", 0, v -> refresh()), actionParams());
        toolbar.addView(action(context, ".button.playAll", "skip_forward_filled.png", 0, v -> playAll()), actionParams());
        toolbar.addView(action(context, ".text.uniPlaylist.addPlatformPlaylists", "list_plus.png", 0,
                v -> showImportDialog()), actionParams());
        toolbar.addView(action(context, ".text.uniPlaylist.add", "audio_lines.png", 0,
                v -> showAddDialog()), actionParams());
        titleView = new TextView(context);
        titleView.setTextSize(Theme.TEXT_SIZE_LARGER);
        titleView.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        info.addView(titleView, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        ScrollView descriptionScroll = new ScrollView(context);
        descriptionScroll.setVerticalScrollBarEnabled(false);
        info.addView(descriptionScroll, new LayoutParams(MATCH_PARENT, 0, 1));
        descriptionView = new TextView(context);
        updateMetadataViews();
        descriptionView.setTextSize(Theme.TEXT_SIZE_NORMAL);
        descriptionView.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        descriptionScroll.addView(descriptionView, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        status = new TextView(context);
        status.setTextSize(Theme.TEXT_SIZE_NORMAL);
        status.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        status.setVisibility(GONE);
        addView(status, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        scroll = new ClampingScrollView(context);
        itemsLayout = new VirtualizedListLayout<>(context, new VirtualizedListLayout.Adapter<>() {
            public long idOf(TuneWeaveUniItem item) { return rowIds.computeIfAbsent(item.id(), ignored -> nextRowId++); }
            public UniRow createItem(ViewGroup parent) { return new UniRow(); }
            public void clearItem(UniRow row) { row.item = null; row.binding = null; row.clearData(); }
            public long boundIdOf(UniRow row) { return row.item == null ? -1 : idOf(row.item); }
            public void bindItem(UniRow row, TuneWeaveUniItem item) {
                row.item = item;
                row.binding = tasks.capture();
                row.bindData(preview(item, idOf(item)));
                int index = items.indexOf(item);
                row.up.setVisibility(index > 0 ? VISIBLE : INVISIBLE);
                row.down.setVisibility(index + 1 < items.size() ? VISIBLE : INVISIBLE);
            }
        });
        itemsLayout.setDefaultItemHeight(dp(64));
        scroll.addView(itemsLayout, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> itemsLayout.updateWindow(y, v.getHeight()));
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> itemsLayout.updateWindow(v.getScrollY(), b - t));
        addView(scroll, new LayoutParams(MATCH_PARENT, 0, 1));
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { tasks.attach(); refresh(); }
            @Override public void onViewDetachedFromWindow(View v) { tasks.detach(); }
        });
    }

    private void refresh() {
        status.setVisibility(VISIBLE);
        status.setText(I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.loading"));
        tasks.<List<TuneWeaveUniItem>>load(progress -> tuneWeave.listUniPlaylistItems(playlist.reference()),
                loaded -> { items = loaded; render(); }, error -> showMessage(error.getMessage()));
    }

    private void render() {
        status.setVisibility(items.isEmpty() ? VISIBLE : GONE);
        status.setText(I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.emptyItems"));
        cover.loadUrl(items.stream().map(TuneWeaveUniItem::coverUrl).filter(url -> url != null && !url.isBlank())
                .findFirst().orElse(MusicHud.ICON_BASE64));
        itemsLayout.updateItems(items);
        itemsLayout.updateWindow(scroll.getScrollY(), scroll.getHeight());
    }

    private MusicDetail preview(TuneWeaveUniItem item, long id) {
        var album = new Album(0, item.album(), item.coverUrl(), "", "", 0,
                new ObservableSequencedSet<>(), new LinkedHashSet<>(), PusherInfo.EMPTY, "");
        var artists = item.artists().stream().map(name -> new Artist(0, name, "", 0, 0, "", List.of(), 0, "")).toList();
        return MusicDetail.fromTuneWeave(id, "", item.kind(), item.title(), Math.max(0, item.durationMillis()), album, artists);
    }

    private final class UniRow extends MusicListItem {
        TuneWeaveUniItem item;
        ScopedViewTasks.Token binding;
        final ImageButton up, down;
        UniRow() {
            super(UniPlaylistDetailView.this.getContext());
            setShowPusherInfo(false);
            setPlatformActionsEnabled(false);
            InsetBackgroundFactory.builder().cornerRadius(dp(7)).inset(dp(1))
                    .padding(new InsetBackgroundFactory.Padding(dp(4), dp(4), dp(4), dp(4)))
                    .build().applyBackgroundTo(this);
            setOnClickListener(v -> { if (current()) play(item); });
            up = rowAction(".button.moveUp", "arrow_left.png", 90, () -> move(items.indexOf(item), items.indexOf(item) - 1));
            down = rowAction(".button.moveDown", "arrow_left.png", -90, () -> move(items.indexOf(item), items.indexOf(item) + 1));
            rowAction(".button.remove", "trash_2.png", 0, () -> remove(item));
        }
        private boolean current() { return item != null && tasks.isCurrent(binding); }
        private ImageButton rowAction(String key, String icon, float rotation, Runnable call) {
            ImageButton button = action(getContext(), key, icon, rotation, ignored -> {});
            button.setOnClickListener(v -> { if (current()) call.run(); });
            getButtonsLayout().addView(button, new LinearLayout.LayoutParams(dp(40), dp(40)));
            return button;
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
        InsetBackgroundFactory.builder().cornerRadius(dp(4)).inset(dp(1)).build().applyBackgroundTo(button);
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
        if (!tasks.canMutate()) return;
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
        itemsLayout.resetItems(List.of());
        status.setVisibility(VISIBLE);
        status.setText(message == null || message.isBlank() ? I18n.get(MusicHud.MOD_ID + ".button.loadingError") : message);
    }
}
