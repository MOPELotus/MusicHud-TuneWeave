package indi.mopelotus.musichud.client.ui.pages.account;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.ui.ClampingScrollView;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.*;
import indi.mopelotus.musichud.client.ui.*;
import indi.mopelotus.musichud.client.ui.components.*;
import indi.mopelotus.musichud.client.ui.layouts.*;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import net.minecraft.client.resources.language.I18n;
import java.util.List;

/** Account catalogs use the same upstream cards and rows as search, with a bounded visible window. */
public final class PersonalLibraryView extends LinearLayout {
    private final ScopedViewTasks tasks = ClientViewTasks.create();
    private final TuneWeavePlatform platform;
    private final TuneWeavePersonalLibrary.Kind kind;
    private final TuneWeavePersonalLibrary.Entry collection;
    private final LinearLayout content;
    private boolean submissionDeletionSupported;
    private List<TuneWeavePersonalLibrary.Entry> currentEntries = List.of();
    private record LibraryPage(java.util.Set<String> capabilities, List<TuneWeavePersonalLibrary.Entry> entries) {}
    private final TextView status;
    private final TuneWeaveClientService service = TuneWeaveClientService.getInstance();
    PersonalLibraryView(Context context, TuneWeavePlatform platform, TuneWeavePersonalLibrary.Kind kind) {
        this(context, platform, kind, null);
    }
    public PersonalLibraryView(Context context, TuneWeavePlatform platform, TuneWeavePersonalLibrary.Kind kind,
                                TuneWeavePersonalLibrary.Entry collection) {
        super(context); this.platform = platform; this.kind = kind; this.collection = collection;
        setOrientation(VERTICAL);
        LinearLayout header = new LinearLayout(context);
        Button back = new Button(context); back.setText(I18n.get(MusicHud.MOD_ID + ".button.back"));
        back.setOnClickListener(v -> { var router = RouterContainer.getInstance(); if (router != null) router.popNavigate(); });
        indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory.builder().cornerRadius(dp(4)).inset(0)
                .padding(new indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory.Padding(dp(12), dp(6), dp(12), dp(6)))
                .build().applyBackgroundTo(back);
        header.addView(back, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        TextView title = new TextView(context); title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR); title.setTextSize(Theme.TEXT_SIZE_LARGE);
        title.setText(collection == null ? I18n.get(MusicHud.MOD_ID + ".library." + kind.key()) : collection.name());
        var titleParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(dp(12), 0, dp(8), 0);
        header.addView(title, titleParams);
        Button refresh = new Button(context); refresh.setText(I18n.get(MusicHud.MOD_ID + ".button.refresh")); refresh.setOnClickListener(v -> load()); styleAction(refresh); header.addView(refresh);
        if (collection != null && collection.resourceKind().equals("digital_album") && platform == TuneWeavePlatform.MIGU) {
            ToggleSubscribeButton subscription = new ToggleSubscribeButton(context);
            subscription.bindState(new indi.mopelotus.musichud.beans.state.ISubscribeState<TuneWeavePersonalLibrary.Entry>() {
                public long getBeanId() { return collection.occurrence(); }
                private <T> java.util.concurrent.CompletableFuture<T> request(java.util.function.Supplier<T> action) {
                    return java.util.concurrent.CompletableFuture.supplyAsync(service.prepareViewRequest(action), MusicHud.EXECUTOR);
                }
                public java.util.concurrent.CompletableFuture<Boolean> isSupported() {
                    return request(() -> service.capabilities(platform).contains("digital_album_subscription_write"));
                }
                public java.util.concurrent.CompletableFuture<Boolean> isSubscribed() {
                    return request(() -> service.loadPersonalLibrary(platform, TuneWeavePersonalLibrary.Kind.DIGITAL_ALBUMS)
                            .stream().anyMatch(item -> item.reference().equals(collection.reference())));
                }
                private java.util.concurrent.CompletableFuture<Void> modify(boolean subscribed) {
                    return request(() -> { service.setDigitalAlbumSubscribed(collection, subscribed); return null; });
                }
                public java.util.concurrent.CompletableFuture<Void> subscribe() { return modify(true); }
                public java.util.concurrent.CompletableFuture<Void> unsubscribe() { return modify(false); }
                public indi.mopelotus.musichud.interfaces.Unregister onOthersModify(java.util.function.Consumer<Boolean> listener) {
                    // Each detail page owns one control; reattachment always reloads the account catalog.
                    return () -> {};
                }
            });
            header.addView(subscription, new LayoutParams(dp(40), dp(40)));
        }
        header.setGravity(icyllis.modernui.view.Gravity.CENTER_VERTICAL);
        var headerParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, dp(24), 0, dp(16)); addView(header, headerParams);
        status = new TextView(context); status.setTextColor(Theme.SECONDARY_TEXT_COLOR); status.setOnClickListener(v -> load()); addView(status);
        ClampingScrollView scroll = new ClampingScrollView(context);
        content = new LinearLayout(context); content.setOrientation(VERTICAL);
        scroll.addView(content, new ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); tasks.attach(); load(); }
    @Override protected void onDetachedFromWindow() { tasks.detach(); super.onDetachedFromWindow(); }
    private void load() {
        content.removeAllViews(); status.setVisibility(VISIBLE); status.setText(I18n.get(MusicHud.MOD_ID + ".text.loading"));
        if (collection == null) tasks.<LibraryPage>load(partial -> new LibraryPage(service.capabilities(platform), service.loadPersonalLibrary(platform, kind)), page -> {
            submissionDeletionSupported = page.capabilities().contains("playlist_submission_record_delete");
            renderEntries(page.entries());
        }, failure -> failed());
        else tasks.load(partial -> service.loadPersonalLibraryTracks(collection), this::renderTracks, failure -> failed());
    }
    private void failed() { status.setVisibility(VISIBLE); status.setText(I18n.get(MusicHud.MOD_ID + ".button.loadingError")); }
    private void ready(boolean empty) { status.setVisibility(empty ? VISIBLE : GONE); status.setText(I18n.get(MusicHud.MOD_ID + ".text.searchNoMoreResult")); }
    private void renderEntries(List<TuneWeavePersonalLibrary.Entry> entries) {
        currentEntries = List.copyOf(entries);
        content.removeAllViews(); ready(entries.isEmpty());
        Object account = MusicEntityCache.captureGeneration();
        VirtualizedCardGrid<TuneWeavePersonalLibrary.Entry> grid = new VirtualizedCardGrid<>(getContext(), 174, 280, entry -> {
            String detail = entry.status().isBlank() ? "" : I18n.get(MusicHud.MOD_ID + ".library.status." + entry.status());
            var card = new CollectionPreviewCard(getContext(), entry.coverUrl(), entry.name(), detail, "");
            if (!detail.isBlank()) {
                var label = new TextView(getContext()); label.setText(detail); label.setMaxLines(3);
                label.setTextSize(Theme.TEXT_SIZE_SMALL); label.setTextColor(Theme.SECONDARY_TEXT_COLOR);
                card.addView(label, new LinearLayout.LayoutParams(dp(156), LayoutParams.WRAP_CONTENT));
            }
            if (!entry.reference().isBlank()) card.setOnClickListener(v -> {
                if (!tasks.isCurrent() || account != MusicEntityCache.captureGeneration()) return;
                var router = RouterContainer.getInstance(); if (router == null) return;
                if (entry.resource() instanceof MusicDetail track) MusicService.getInstance().sendPushMusicToQueue(track);
                else if (entry.resource() instanceof Album album) router.pushNavigate(new MusicCollectionDetailView(getContext(), album));
                else router.pushNavigate(new PersonalLibraryView(getContext(), platform, kind, entry));
            });
            if (kind == TuneWeavePersonalLibrary.Kind.SUBMISSIONS && submissionDeletionSupported) {
                Button delete = new Button(getContext());
                delete.setText(I18n.get(MusicHud.MOD_ID + ".button.deleteSubmissionRecords"));
                delete.setOnClickListener(v -> confirmDeleteRecords(entry));
                styleAction(delete);
                card.addView(delete, new LayoutParams(dp(156), LayoutParams.WRAP_CONTENT));
            }
            return card;
        });
        grid.setItems(entries); content.addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }
    private void styleAction(Button button) {
        indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory.builder()
                .cornerRadius(dp(4)).inset(dp(1))
                .padding(new indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory.Padding(dp(8), dp(8), dp(8), dp(8)))
                .build().applyBackgroundTo(button);
    }
    private void confirmDeleteRecords(TuneWeavePersonalLibrary.Entry entry) {
        var binding = tasks.capture();
        if (!tasks.canMutate()) return;
        TextView title = new TextView(getContext()); title.setText(entry.name());
        TextView warning = new TextView(getContext());
        warning.setText(I18n.get(MusicHud.MOD_ID + ".text.submission.deleteWarning"));
        new Modal(getContext(), title, warning,
                new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.confirm"), (button, modal) -> {
                    modal.dismiss();
                    var result = new java.util.concurrent.atomic.AtomicReference<TuneWeaveSubmissionOutcome>();
                    tasks.mutate(binding, () -> result.set(service.deleteSubmissionRecords(entry.reference())), () -> {
                        var outcome = result.get();
                        if (outcome.acknowledged()) renderEntries(currentEntries.stream()
                                .filter(item -> !item.reference().equals(entry.reference())).toList());
                        status.setVisibility(VISIBLE); status.setText(submissionMessage(outcome, true));
                    }, error -> failed());
                }), new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.cancel"), (button, modal) -> modal.dismiss())).show();
    }
    static String submissionMessage(TuneWeaveSubmissionOutcome outcome, boolean deletion) {
        String prefix = MusicHud.MOD_ID + ".text.submission.";
        String message = I18n.get(prefix + (outcome.acknowledged() ? deletion ? "recordsRemoved" : "accepted" : "notConfirmed"), outcome.removedRecords());
        if (outcome.metadataUpdated()) message += " · " + I18n.get(prefix + "metadataUpdated");
        if (outcome.ownedPlaylistPresent() != null) message += " · " + I18n.get(prefix + (outcome.ownedPlaylistPresent() ? "playlistRetained" : "playlistAbsent"));
        return message + " · " + I18n.get(prefix + (outcome.published() == null ? "publicationUnknown" : outcome.published() ? "published" : "notPublished"));
    }
    private record TrackRow(long occurrence, MusicDetail track) {}
    private void renderTracks(List<MusicDetail> tracks) {
        content.removeAllViews(); ready(tracks.isEmpty());
        var list = new ViewportListLayout<TrackRow, MusicListItem>(getContext(), new VirtualizedListLayout.Adapter<>() {
            public long idOf(TrackRow row) { return row.occurrence(); }
            public MusicListItem createItem(ViewGroup parent) { return MusicListFactory.createItem(parent, view -> !tasks.isCurrent()); }
            public void clearItem(MusicListItem view) { view.clearData(); view.setTag(null); }
            public long boundIdOf(MusicListItem view) { return view.getTag() instanceof Long id ? id : -1; }
            public void bindItem(MusicListItem view, TrackRow row) { view.setTag(row.occurrence()); view.bindData(row.track()); }
        });
        list.setDefaultItemHeight(dp(72)); list.setAnimationsEnabled(false);
        list.resetItems(java.util.stream.IntStream.range(0, tracks.size()).mapToObj(i -> new TrackRow(i, tracks.get(i))).toList());
        content.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }
}
