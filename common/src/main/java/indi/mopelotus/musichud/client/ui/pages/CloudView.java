package indi.mopelotus.musichud.client.ui.pages;

import indi.mopelotus.musichud.client.ui.ScopedViewTasks;
import indi.mopelotus.musichud.client.ui.ClientViewTasks;

import icyllis.modernui.R;
import icyllis.modernui.animation.LayoutTransition;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.drawable.InsetDrawable;
import icyllis.modernui.widget.*;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.mc.ui.ClampingScrollView;
import indi.mopelotus.musichud.client.ui.components.CloudTrackItem;
import indi.mopelotus.musichud.client.ui.dto.CloudTrackEntry;
import indi.mopelotus.musichud.client.ui.dto.CloudEntryState;
import indi.mopelotus.musichud.client.services.cloud.CloudUploadService;
import indi.mopelotus.musichud.client.services.cloud.CloudUploadTask;
import indi.mopelotus.musichud.client.ui.layouts.VirtualizedListLayout;
import indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.interfaces.Unregister;
import java.util.*;
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
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
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
    private final VirtualizedListLayout<CloudTrackEntry, CloudTrackItem> rows;
    private final ClampingScrollView scrollView;
    private final ProgressBar loadingRing;
    private final ProgressBar usageBar;
    private final LinearLayout usageInfo;
    private final TextView usageText;
    private final int usageBarWidth;
    private final CloudUploadService uploads = CloudUploadService.getInstance();
    private Unregister uploadSubscription;
    private List<TuneWeaveCloudTrack> libraryTracks = List.of();
    private final Map<String, Long> cloudIds = new HashMap<>();
    private long nextCloudId = 1;
    private final Map<Long, Runnable> scheduledCompletion = new HashMap<>();
    private Object viewLifetime;
    private Object libraryAccount;
    private final Map<Long, Preview> previews = new HashMap<>();
    private record Preview(String song, String artist, String album, String cover, long size,
                           int duration, long bitrate, TuneWeaveCloudTrack track) {}
    private final Set<Long> refreshedUploads = new HashSet<>();
    private final TextView status;

    public CloudView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setLayoutParams(new LayoutParams(MATCH_PARENT, MATCH_PARENT));

        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setMinimumHeight(dp(48));
        topBar.setLayoutTransition(new LayoutTransition());
        LayoutParams topBarParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        topBarParams.setMargins(0, dp(24), 0, dp(16));
        addView(topBar, topBarParams);

        ImageButton backButton = new ImageButton(context);
        backButton.setTooltipText(I18n.get(MusicHud.MOD_ID + ".button.back"));
        Image backIcon = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/arrow_left.png");
        if (backIcon != null) {
            backButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            backButton.setImageDrawable(new ScaledImageDrawable(getContext().getResources(), backIcon, dp(16), dp(16)));
        }
        backButton.setOnClickListener(view -> {
            RouterContainer routerContainer = RouterContainer.getInstance();
            if (routerContainer != null) {
                routerContainer.popNavigate();
            }
        });
        InsetBackgroundFactory.builder()
                .inset(0)
                .cornerRadius(dp(4))
                .padding(new InsetBackgroundFactory.Padding(dp(16), 0, dp(16), 0))
                .build()
                .applyBackgroundTo(backButton);
        LayoutParams backButtonParams = new LayoutParams(WRAP_CONTENT, MATCH_PARENT);
        backButtonParams.setMargins(0, 0, dp(4), 0);
        topBar.addView(backButton, backButtonParams);

        TextView title = new TextView(context);
        title.setTextSize(Theme.TEXT_SIZE_LARGER);
        title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        title.setText(I18n.get(MusicHud.MOD_ID + ".button.cloud"));
        LayoutParams titleParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        titleParams.setMargins(dp(12), 0, dp(12), 0);
        topBar.addView(title, titleParams);

        InsetBackgroundFactory iconBackgroundFactory = InsetBackgroundFactory.builder()
                .backgroundColor(Theme.GHOST_BUTTON_STATES)
                .inset(dp(1))
                .cornerRadius(dp(4))
                .padding(new InsetBackgroundFactory.Padding(dp(4), dp(4), dp(4), dp(4)))
                .build();

        ImageButton refreshButton = new ImageButton(context);
        iconBackgroundFactory.applyBackgroundTo(refreshButton);
        refreshButton.setTooltipText(I18n.get(MusicHud.MOD_ID + ".button.refresh"));
        refreshButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        Image refreshIcon = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/rotate_cw.png");
        if (refreshIcon != null) {
            refreshButton.setImageDrawable(new InsetDrawable(
                    new ScaledImageDrawable(getContext().getResources(), refreshIcon, dp(12), dp(16)), dp(3)));
        }
        refreshButton.setOnClickListener(view -> refresh());
        topBar.addView(refreshButton, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        ImageButton uploadButton = new ImageButton(context);
        iconBackgroundFactory.applyBackgroundTo(uploadButton);
        uploadButton.setTooltipText(I18n.get(MusicHud.MOD_ID + ".button.upload"));
        uploadButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        Image uploadIcon = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/upload.png");
        if (uploadIcon != null) {
            uploadButton.setImageDrawable(new InsetDrawable(
                    new ScaledImageDrawable(getContext().getResources(), uploadIcon, dp(12), dp(16)), dp(3)));
        }
        uploadButton.setOnClickListener(view -> selectUpload());
        topBar.addView(uploadButton, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        topBar.addView(action(".button.import", v -> showImport()), new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        usageInfo = new LinearLayout(context);
        usageInfo.setVisibility(GONE);
        usageInfo.setOrientation(HORIZONTAL);
        usageInfo.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams params = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params.setMargins(dp(12), 0, 0, 0);
        topBar.addView(usageInfo, params);
        {
            usageBar = new ProgressBar(context, null, R.attr.progressBarStyleHorizontal);
            usageBarWidth = dp(160);
            usageBar.setMax(usageBarWidth);
            LayoutParams params1 = new LayoutParams(usageBarWidth, WRAP_CONTENT);
            params1.setMargins(0, 0, dp(8), 0);
            usageInfo.addView(usageBar, params1);
        }
        {
            usageText = new TextView(context, null);
            usageText.setTextSize(Theme.TEXT_SIZE_NORMAL);
            usageText.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            usageInfo.addView(usageText, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        }

        status = new TextView(context);
        status.setTextSize(Theme.TEXT_SIZE_NORMAL);
        status.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        status.setPadding(dp(12), 0, dp(12), dp(8));
        addView(status, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        FrameLayout content = new FrameLayout(context);
        addView(content, new LayoutParams(MATCH_PARENT, 0, 1));
        scrollView = new ClampingScrollView(context);
        scrollView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        content.addView(scrollView, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        rows = new VirtualizedListLayout<>(context, new VirtualizedListLayout.Adapter<>() {
            public long idOf(CloudTrackEntry entry) { return entry.id(); }
            public CloudTrackItem createItem(ViewGroup parent) { return new CloudTrackItem(context); }
            public void clearItem(CloudTrackItem item) { item.clearData(); }
            public long boundIdOf(CloudTrackItem item) { return item.boundId(); }
            public void bindItem(CloudTrackItem item, CloudTrackEntry entry) {
                var token = tasks.capture();
                item.setActionsAllowed(() -> tasks.isCurrent(token));
                item.setOnDelete(e -> { if (tasks.isCurrent(token)) confirmDelete(e.cloudTrackInfo()); });
                item.setOnMore(e -> { if (tasks.isCurrent(token)) showTrackActions(e.cloudTrackInfo()); });
                item.setOnRetry(e -> { if (tasks.isCurrent(token)) uploads.retry(e.id()); });
                item.setOnCancel(e -> { if (tasks.isCurrent(token)) uploads.cancel(e.id()); });
                item.setOnRemove(e -> { if (tasks.isCurrent(token)) uploads.remove(e.id()); });
                item.bindData(entry);
            }
        });
        rows.setDefaultItemHeight(dp(72));
        scrollView.addView(rows, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        scrollView.setOnScrollChangeListener((v, x, y, oldX, oldY) -> rows.updateWindow(y, v.getHeight()));
        scrollView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> rows.updateWindow(v.getScrollY(), b - t));
        loadingRing = new ProgressBar(context);
        loadingRing.setIndeterminate(true);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(dp(48), dp(48));
        loadingParams.gravity = Gravity.CENTER;
        content.addView(loadingRing, loadingParams);
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                tasks.attach();
                viewLifetime = new Object();
                uploadSubscription = uploads.addOnChange(() -> {
                    var token = tasks.capture();
                    MuiModApi.postToUiThread(() -> {
                        if (!tasks.isCurrent(token)) return;
                        rebuildRows();
                        refreshCompletedUploads();
                    });
                });
                refresh();
            }
            @Override public void onViewDetachedFromWindow(View v) {
                tasks.detach();
                if (uploadSubscription != null) { uploadSubscription.unregister(); uploadSubscription = null; }
                viewLifetime = null;
                scheduledCompletion.values().forEach(CloudView.this::removeCallbacks);
                scheduledCompletion.clear();
            }
        });
    }

    private void refresh() {
        boolean refresh = !tasks.failed();
        Object account = indi.mopelotus.musichud.client.services.music.MusicEntityCache.captureGeneration();
        if (libraryAccount != account) {
            libraryAccount = account;
            libraryTracks = List.of();
            cloudIds.clear();
            refreshedUploads.clear();
            previews.clear();
            rows.resetItems(List.of());
            usageInfo.setVisibility(GONE);
        }
        // A refresh accounts for completions that existed when its request started.
        for (var task : uploads.snapshot()) {
            if (isCompleted(task)) refreshedUploads.add(task.getId());
        }
        showStatus(I18n.get(MusicHud.MOD_ID + ".text.cloud.loading"));
        loadingRing.setVisibility(libraryTracks.isEmpty() ? VISIBLE : GONE);
        tasks.load(progress -> tuneWeave.loadCloudLibrary(refresh, progress), this::render,
                error -> { loadingRing.setVisibility(GONE); showStatus(message(error)); });
    }

    private void render(TuneWeaveCloudLibrary library) {
        loadingRing.setVisibility(GONE);
        libraryTracks = library.tracks();
        usageInfo.setVisibility(library.storageSize() >= 0 ? VISIBLE : GONE);
        usageText.setText(formatBytes(library.storageSize()) + " / " + formatBytes(library.storageMaxSize()));
        usageBar.setVisibility(library.storageMaxSize() > 0 ? VISIBLE : GONE);
        usageBar.setProgress(library.storageMaxSize() > 0
                ? (int) Math.clamp(Math.round((double) library.storageSize() / library.storageMaxSize() * usageBarWidth), 0, usageBarWidth) : 0);
        showStatus(library.tracks().isEmpty() ? I18n.get(MusicHud.MOD_ID + ".text.cloud.empty")
                : I18n.get(MusicHud.MOD_ID + ".text.cloud.count").replace("{}", Long.toString(library.total())));
        Set<String> loaded = new HashSet<>();
        for (var track : libraryTracks) { loaded.add(track.reference()); loaded.add(track.track().getSourceRef()); }
        uploads.reconcileReferences(loaded);
        rebuildRows();
        refreshCompletedUploads();
    }

    private static boolean isCompleted(CloudUploadTask task) {
        return task.getState() == CloudEntryState.COMPLETED || task.getState() == CloudEntryState.COMPLETED_PRESENTED;
    }

    private void refreshCompletedUploads() {
        if (tasks.canMutate() && uploads.snapshot().stream()
                .anyMatch(task -> isCompleted(task) && !refreshedUploads.contains(task.getId()))) refresh();
    }

    private void rebuildRows() {
        List<CloudTrackEntry> entries = new ArrayList<>();
        for (CloudUploadTask task : uploads.snapshot()) {
            TuneWeaveCloudTrack matched = libraryTracks.stream().filter(track ->
                    !Objects.requireNonNullElse(task.getResolvedTrackId(), "").isBlank()
                    && (task.getResolvedTrackId().equals(track.reference())
                        || task.getResolvedTrackId().equals(track.track().getSourceRef()))).findFirst().orElse(null);
            entries.add(new CloudTrackEntry(task.getId(), matched == null ? uploadPreview(task) : matched, task));
            if (task.getState() == CloudEntryState.COMPLETED && !scheduledCompletion.containsKey(task.getId())) {
                Object lifetime = viewLifetime;
                Object account = libraryAccount;
                Runnable complete = () -> {
                    scheduledCompletion.remove(task.getId());
                    if (viewLifetime != lifetime || libraryAccount != account || !tasks.isCurrent()) return;
                    uploads.markPresented(task.getId());
                    Set<String> refs = new HashSet<>();
                    for (var track : libraryTracks) { refs.add(track.reference()); refs.add(track.track().getSourceRef()); }
                    uploads.reconcileReferences(refs);
                    rebuildRows();
                };
                scheduledCompletion.put(task.getId(), complete);
                postDelayed(complete, CloudUploadService.COMPLETION_PRESENTATION_MILLIS);
            }
        }
        for (TuneWeaveCloudTrack track : libraryTracks) {
            boolean showingCompletion = entries.stream().anyMatch(entry -> entry.cloudTrackInfo() == track);
            if (!showingCompletion) entries.add(new CloudTrackEntry(cloudIds.computeIfAbsent(track.reference(), key -> nextCloudId++), track, null));
        }
        Set<Long> activeUploads = new HashSet<>();
        for (var entry : entries) if (entry.task() != null) activeUploads.add(entry.id());
        previews.keySet().retainAll(activeUploads);
        rows.updateItems(entries);
        rows.updateWindow(scrollView.getScrollY(), scrollView.getHeight());
    }

    private TuneWeaveCloudTrack uploadPreview(CloudUploadTask task) {
        Preview cached = previews.get(task.getId());
        if (cached != null && Objects.equals(cached.song(), task.getSong())
                && Objects.equals(cached.artist(), task.getArtist()) && Objects.equals(cached.album(), task.getAlbum())
                && Objects.equals(cached.cover(), task.getCoverDataUri()) && cached.size() == task.getFileSize()
                && cached.duration() == task.getDurationMillis() && cached.bitrate() == task.getBitrate()) return cached.track();
        var album = new indi.mopelotus.musichud.beans.music.Album(0, task.getAlbum(), task.getCoverDataUri(), "", "", 0,
                new indi.mopelotus.musichud.utils.collections.ObservableSequencedSet<>(), new LinkedHashSet<>(),
                indi.mopelotus.musichud.beans.music.PusherInfo.EMPTY, "");
        List<Artist> artists = task.getArtist().isBlank() ? List.of()
                : List.of(new Artist(0, task.getArtist(), "", 0, 0, "", List.of(), 0, ""));
        var detail = MusicDetail.fromTuneWeave(task.getId(), "", "cloud_upload",
                task.getSong().isBlank() ? task.getFileName() : task.getSong(), task.getDurationMillis(), album, artists);
        var track = new TuneWeaveCloudTrack("", detail, task.getFileName(), task.getFileSize(), "", task.getBitrate(), "", "", "");
        previews.put(task.getId(), new Preview(task.getSong(), task.getArtist(), task.getAlbum(), task.getCoverDataUri(),
                task.getFileSize(), task.getDurationMillis(), task.getBitrate(), track));
        return track;
    }

    private void selectUpload() {
        var token = tasks.capture();
        if (!tasks.canMutate()) return;
        String paths = TinyFileDialogs.tinyfd_openFileDialog(I18n.get(MusicHud.MOD_ID + ".text.cloud.upload"),
                "", null, "Audio files", true);
        if (paths == null || paths.isBlank() || !tasks.isCurrent(token)) return;
        try {
            for (String path : paths.split("\\|")) uploads.enqueue(Path.of(path));
            rebuildRows();
        } catch (RuntimeException error) { showStatus(message(error)); }
    }

    private void showTrackActions(TuneWeaveCloudTrack track) {
        if (!tasks.canMutate()) return;
        LinearLayout actions = form();
        Modal[] holder = new Modal[1];
        actions.addView(action(".button.download", v -> { holder[0].dismiss(); selectDownload(track); }));
        actions.addView(action(".button.match", v -> { holder[0].dismiss(); showMatch(track); }));
        actions.addView(action(".button.lyrics", v -> { holder[0].dismiss(); showLyrics(track); }));
        holder[0] = new Modal(getContext(), title(track.track().getName()), actions, cancel());
        holder[0].show();
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
        InsetBackgroundFactory.builder().cornerRadius(dp(4)).inset(dp(1))
                .build().applyBackgroundTo(button);
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
