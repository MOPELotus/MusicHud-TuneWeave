package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.animation.LayoutTransition;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.ui.ClampingScrollView;
import indi.mopelotus.musichud.client.ui.layouts.VirtualizedListLayout;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.drawable.Drawable;
import icyllis.modernui.graphics.drawable.InsetDrawable;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.text.SpannableString;
import icyllis.modernui.text.Spanned;
import icyllis.modernui.text.style.ImageSpan;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.beans.user.Profile;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.CallbackGeneration;
import indi.mopelotus.musichud.client.ui.AsyncCollectionMutation;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import indi.mopelotus.musichud.interfaces.Unregister;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import indi.mopelotus.musichud.utils.CollectionUpdateNotifier;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;
import net.minecraft.client.resources.language.I18n;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public class MusicCollectionDetailView extends LinearLayout {
    private static final MusicService musicService = MusicService.getInstance();
    private final ProgressBar progressBar;
    private final VirtualizedListLayout<MusicDetail, MusicListItem> tracksListView;
    private final ClampingScrollView scrollView;
    private final UrlImageView imageView;
    private final int imageSize = dp(128);
    private final TextView extraInfo;
    private TextView nameView;
    private TextView playedCountView;
    private TextView musicTrackCountView;
    private MusicCollection musicCollection;
    private final boolean editablePlaylist;
    private Unregister tracksSyncUnregister = null;
    private Unregister updateNotifierUnregister = null;
    private final AtomicBoolean syncPending = new AtomicBoolean();
    private final CallbackGeneration callbacks = new CallbackGeneration();
    private long viewGeneration;
    private boolean detached;
    private boolean loadFailed;
    private boolean detailComplete;
    private Object detailScope;
    private final AsyncCollectionMutation edits = new AsyncCollectionMutation();
    private String currentCoverUrl = null;
    private long collectionId = -1;
    private boolean albumCollection = false;

    public MusicCollectionDetailView(Context context, MusicCollection musicCollection) {
        this(context, musicCollection, false);
    }

    public MusicCollectionDetailView(Context context, MusicCollection musicCollection, boolean editablePlaylist) {
        super(context);

        this.musicCollection = musicCollection;
        this.editablePlaylist = editablePlaylist;
        setOrientation(VERTICAL);
        String collectionNameI18n = musicCollection.getNameI18nKey();

        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(HORIZONTAL);

        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        topBar.setLayoutParams(params);

        ImageButton backButton = new ImageButton(context);
        String tooltipText = I18n.get(MusicHud.MOD_ID + ".button.back");
        Image image = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/arrow_left.png");
        if (image != null) {
            backButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            backButton.setImageDrawable(new ScaledImageDrawable(getContext().getResources(), image, dp(16), dp(16)));
        }
        backButton.setTooltipText(tooltipText);
        backButton.setOnClickListener(view -> {
            RouterContainer router = RouterContainer.getInstance();
            if (router != null) router.popNavigate();
        });
        InsetBackgroundFactory drawable = InsetBackgroundFactory.builder()
                .inset(0)
                .cornerRadius(dp(4))
                .padding(new InsetBackgroundFactory.Padding(dp(16), 0, dp(16), 0))
                .build();
        drawable.applyBackgroundTo(backButton);
        LayoutParams backButtonParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        backButtonParams.setMargins(0, 0, dp(4), 0);
        topBar.addView(backButton, backButtonParams);

        imageView = new UrlImageView(context);
        LayoutParams imageParams = new LayoutParams(imageSize, imageSize);
        topBar.addView(imageView, imageParams);
        imageView.loadUrl(musicCollection.getImageThumbnailUrl(imageSize));
        imageView.setCornerRadius(dp(8));

        LinearLayout briefInfo = new LinearLayout(context);
        briefInfo.setGravity(Gravity.TOP);
        briefInfo.setOrientation(VERTICAL);
        LayoutParams params1 = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1);
        params1.setMargins(dp(16), 0, 0, 0);
        topBar.addView(briefInfo, params1);

        FlexWrapLayout row1 = new FlexWrapLayout(context);
        row1.setAnimationsEnabled(false);
        LayoutParams row1Params = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        row1Params.setMargins(0, 0, 0, dp(2));
        briefInfo.addView(row1, row1Params);

        TextView typeText = new TextView(context);
        typeText.setTextSize(Theme.TEXT_SIZE_LARGE);
        typeText.setTextColor(Theme.NORMAL_TEXT_COLOR);
        typeText.setText(I18n.get(collectionNameI18n));
        LayoutParams typeParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        typeParams.setMargins(0, 0, dp(16), 0);
        row1.addView(typeText, typeParams);

        if (musicCollection instanceof Playlist playlist) {
            {
                musicTrackCountView = new TextView(context);
                musicTrackCountView.setTextSize(Theme.TEXT_SIZE_LARGE);
                updatePlaylistTrackCountView(playlist);
                LayoutParams params3 = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 0);
                params3.setMargins(0, 0, dp(12), 0);
                row1.addView(musicTrackCountView, params3);
            }
            {
                playedCountView = new TextView(context);
                playedCountView.setTextSize(Theme.TEXT_SIZE_LARGE);
                SpannableString text = new SpannableString("  " + indi.mopelotus.musichud.client.utils.CountFormatter.formatCount(playlist.getDisplayPlayedCount()));
                Image icon = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/audio_lines.png");
                if (icon != null) {
                    ImageSpan iconSpan = ImageUtils.getIconSpan(icon);
                    text.setSpan(iconSpan, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                playedCountView.setText(text);
                row1.addView(playedCountView, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 0));
            }
        } else if (musicCollection instanceof Album album) {
            {
                musicTrackCountView = new TextView(context);
                musicTrackCountView.setTextSize(Theme.TEXT_SIZE_LARGE);
                updateAlbumTrackCountView(album);
                LayoutParams params3 = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 0);
                params3.setMargins(0, 0, dp(12), 0);
                row1.addView(musicTrackCountView, params3);
            }
            String type = album.getType();
            if (!type.isBlank()) {
                TextView albumTypeText = new TextView(context);
                albumTypeText.setTextSize(Theme.TEXT_SIZE_LARGE);
                SpannableString text = new SpannableString("  " + mappedAlbumType(type));
                Image icon = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/disc_album.png");
                if (icon != null) {
                    ImageSpan iconSpan = ImageUtils.getIconSpan(icon);
                    text.setSpan(iconSpan, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                albumTypeText.setText(text);
                row1.addView(albumTypeText, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 0));
            }
        }

        row1.addView(new View(context), new LayoutParams(dp(16), MATCH_PARENT, 0));

        InsetBackgroundFactory backgroundFactory = InsetBackgroundFactory.builder()
                .backgroundColor(Theme.GHOST_BUTTON_STATES)
                .inset(0)
                .cornerRadius(dp(4))
                .padding(new InsetBackgroundFactory.Padding(dp(2), dp(2), dp(2), dp(2)))
                .build();
        int dp28 = dp(28);
        {
            ImageButton refreshButton = new ImageButton(context);
            backgroundFactory.applyBackgroundTo(refreshButton);
            refreshButton.setTooltipText(I18n.get(MusicHud.MOD_ID + ".button.refresh"));
            refreshButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            var resources = getContext().getResources();
            Image image1 = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/rotate_cw.png");
            refreshButton.setImageDrawable(new InsetDrawable(new ScaledImageDrawable(resources, image1, dp(12), dp(16)), dp(3)));
            refreshButton.setOnClickListener((v) -> {
                refreshData(!loadFailed);
            });
            row1.addView(refreshButton, new LayoutParams(dp28, dp28));
        }
        {
            ToggleSubscribeButton toggleSubscribeButton = new ToggleSubscribeButton(context);
            backgroundFactory.applyBackgroundTo(toggleSubscribeButton);
            row1.addView(toggleSubscribeButton, new LayoutParams(dp28, dp28, 0));
            if (musicCollection instanceof Playlist playlist) {
                Profile current = Profile.getCurrent();
                if (current == null || current.equals(Profile.ANONYMOUS) || playlist.getCreator().getUserId() == current.getUserId()) {
                    toggleSubscribeButton.setVisibility(GONE);
                } else {
                    var subscribeState = musicService.getPlaylistSubscribeState(playlist);
                    toggleSubscribeButton.bindState(subscribeState);
                }
            } else if (musicCollection instanceof Album album) {
                var subscribeState = musicService.getAlbumSubscribeState(album);
                toggleSubscribeButton.bindState(subscribeState);
            }
        }
        row1.addView(new IdlePlaySourceWidget(context, musicCollection, dp28),
                new LayoutParams(WRAP_CONTENT, dp28));

        if (musicCollection instanceof Playlist playlist
                && TuneWeaveClientService.getInstance().supportsFavoriteIntelligence(playlist)) {
            ToggleFavoriteIntelligenceButton intelligenceButton =
                    new ToggleFavoriteIntelligenceButton(context);
            backgroundFactory.applyBackgroundTo(intelligenceButton);
            intelligenceButton.bindPlaylist(playlist);
            row1.addView(intelligenceButton, new LayoutParams(dp28, dp28, 0));
        }

        nameView = new TextView(context);
        nameView.setTextSize(Theme.TEXT_SIZE_LARGER);
        nameView.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        LayoutParams nameParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        nameParams.setMargins(0, 0, 0, dp(8));
        briefInfo.addView(nameView, nameParams);
        ScrollView descriptionScrollView = new ScrollView(context);
        descriptionScrollView.setVerticalScrollBarEnabled(false);
        briefInfo.addView(descriptionScrollView, new LayoutParams(MATCH_PARENT, 0, 1));
        extraInfo = new TextView(context);
        extraInfo.setTextSize(Theme.TEXT_SIZE_NORMAL);
        extraInfo.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        descriptionScrollView.addView(extraInfo, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        updateHeader(musicCollection);

        LayoutParams topBarParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        topBarParams.setMargins(0, dp(24), 0, 0);
        addView(topBar, topBarParams);

        progressBar = new ProgressBar(context);
        LayoutParams progressParams = new LayoutParams(MATCH_PARENT, MATCH_PARENT);
        progressParams.setMargins(0, dp(32), 0, 0);
        addView(progressBar, progressParams);

        scrollView = new ClampingScrollView(context);
        scrollView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        scrollView.setFillViewport(true);
        LayoutParams tracksParams = new LayoutParams(MATCH_PARENT, MATCH_PARENT);
        tracksParams.setMargins(0, dp(24), 0, 0);
        addView(scrollView, tracksParams);

        tracksListView = new VirtualizedListLayout<>(context, new VirtualizedListLayout.Adapter<>() {
            public long idOf(MusicDetail track) { return track.getId(); }
            public MusicListItem createItem(ViewGroup parent) { return createTrackItem(context); }
            public void clearItem(MusicListItem item) { item.clearData(); }
            public void bindItem(MusicListItem item, MusicDetail track) {
                item.bindData(track);
                setItemEditingEnabled(item);
            }
            public long boundIdOf(MusicListItem item) {
                return item.getMusicDetail() == null ? -1 : item.getMusicDetail().getId();
            }
        });
        tracksListView.setDefaultItemHeight(dp(64));
        scrollView.addView(tracksListView, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        scrollView.setOnScrollChangeListener((v, x, y, oldX, oldY) -> updateTrackWindow());
        scrollView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateTrackWindow());

        if (musicCollection instanceof Playlist playlist) {
            collectionId = playlist.getId();
            albumCollection = false;
        } else if (musicCollection instanceof Album album) {
            collectionId = album.getId();
            albumCollection = true;
        }
        refreshData(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (detached) {
            detached = false;
            refreshData(false);
        }
        registerUpdateNotifier();
    }

    private void registerUpdateNotifier() {
        if (updateNotifierUnregister != null || collectionId < 0) return;
        long generation = viewGeneration;
        Runnable notified = () -> {
            if (callbacks.isCurrent(generation)) onCollectionUpdateNotified(generation);
        };
        updateNotifierUnregister = albumCollection
                ? CollectionUpdateNotifier.registerAlbum(collectionId, notified)
                : CollectionUpdateNotifier.registerPlaylist(collectionId, notified);
    }

    private void onCollectionUpdateNotified(long generation) {
        // Path 1: list sync from cache (instant, no network round-trip, never
        // overrides the local optimistic state with an in-flight server state)
        callbacks.post(MuiModApi::postToUiThread, generation, () -> {
            if (!isAttachedToWindow()) return;
            MusicCollection latest = albumCollection
                    ? MusicEntityCache.getAlbum(collectionId)
                    : MusicEntityCache.getPlaylist(collectionId);
            if (latest != null && latest != musicCollection) {
                musicCollection = latest;
                unregisterTracksSync();
                registerTracksSync(latest);
            }
            updateHeader(musicCollection);
            syncTracksView();
        });
        // Path 2: cover refresh from network (latest URL only)
        CompletableFuture<? extends MusicCollection> future;
        if (albumCollection) {
            future = musicService.loadAlbumDetail(collectionId, true);
        } else {
            future = musicService.loadPlaylistDetail(collectionId, true);
        }
        future.whenComplete((latest, throwable) -> {
            if (throwable != null || latest == null) return;
            callbacks.post(MuiModApi::postToUiThread, generation, () -> {
                if (!isAttachedToWindow()) return;
                String newCoverUrl = latest.getImageThumbnailUrl(imageSize);
                if (!Objects.equals(currentCoverUrl, newCoverUrl)) {
                    currentCoverUrl = newCoverUrl;
                    imageView.loadUrl(newCoverUrl);
                }
            });
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        detached = true;
        viewGeneration = callbacks.next();
        syncPending.set(false);
        unregisterTracksSync();
        if (updateNotifierUnregister != null) {
            updateNotifierUnregister.unregister();
            updateNotifierUnregister = null;
        }
    }

    private void updateHeader(MusicCollection collection) {
        nameView.setText(collection.getName());
        if (collection instanceof Playlist playlist) {
            extraInfo.setText(playlist.getDescription());
            if (playedCountView != null) {
                SpannableString text = new SpannableString("  " + indi.mopelotus.musichud.client.utils.CountFormatter.formatCount(playlist.getDisplayPlayedCount()));
                Image icon = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/audio_lines.png");
                if (icon != null) text.setSpan(ImageUtils.getIconSpan(icon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                playedCountView.setText(text);
            }
        } else if (collection instanceof Album album) {
            String company = album.getCompany();
            String artists = album.getArtists().stream().map(Artist::getName).collect(Collectors.joining(" / "));
            String alias = String.join(" | ", album.getAlias());
            extraInfo.setText((company.isBlank() ? "" : company + "\n") + artists + (alias.isBlank() ? "" : "\n" + alias)
                    + (album.getDescription().isBlank() ? "" : "\n" + album.getDescription()));
        }
    }

    private void updatePlaylistTrackCountView(Playlist playlist) {
        SpannableString text = new SpannableString("  " + playlist.getMusicTrackCount());
        String iconPath = TuneWeaveClientService.getInstance().isFavoritePlaylist(playlist) ? "heart_filled.png" : playlist.isPersonalized() ? "radio.png" : "list_music.png";
        Image icon = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/" + iconPath);
        if (icon != null) {
            ImageSpan iconSpan = ImageUtils.getIconSpan(icon);
            text.setSpan(iconSpan, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        musicTrackCountView.setText(text);
    }

    private void updateAlbumTrackCountView(Album album) {
        int musicTrackCount = Math.max(album.getMusicTrackCount(), album.getMusicDetails().size());
        if (musicTrackCount <= 0) {
            musicTrackCountView.setVisibility(GONE);
            return;
        }
        musicTrackCountView.setVisibility(VISIBLE);
        SpannableString text = new SpannableString("  " + musicTrackCount);
        Image icon = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/disc_album.png");
        if (icon != null) {
            ImageSpan iconSpan = ImageUtils.getIconSpan(icon);
            text.setSpan(iconSpan, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        musicTrackCountView.setText(text);
    }

    private void refreshData(boolean ignoreCache) {
        loadFailed = false;
        detailComplete = false;
        Object loadingScope = detailScope = MusicEntityCache.captureGeneration();
        long generation = viewGeneration = callbacks.next();
        unregisterTracksSync();
        if (updateNotifierUnregister != null) {
            updateNotifierUnregister.unregister();
            updateNotifierUnregister = null;
        }
        if (isAttachedToWindow()) registerUpdateNotifier();
        Context context = getContext();
        tracksListView.resetItems(List.of());
        syncPending.set(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        // Keep an already materialized partial/complete snapshot interactive while the
        // detail request fills missing pages. The request below remains authoritative.
        Collection<MusicDetail> cachedTracks = musicCollection.getMusicDetails();
        tracksListView.resetItems(cachedTracks.stream().filter(Objects::nonNull)
                .filter(track -> track != MusicDetail.NONE).toList());
        if (!cachedTracks.isEmpty()) {
            progressBar.setVisibility(GONE);
            registerTracksSync(musicCollection);
        }
        MusicService.getInstance().loadMoreMusicOfCollection(musicCollection, ignoreCache, partial ->
                callbacks.post(MuiModApi::postToUiThread, generation, () -> {
                    if (loadingScope != MusicEntityCache.captureGeneration()) return;
                    this.musicCollection = partial;
                    updateHeader(partial);
                    if (!partial.getMusicDetails().isEmpty()) progressBar.setVisibility(GONE);
                    registerTracksSync(partial);
                    syncTracksList(partial.getMusicDetails());
                    if (musicTrackCountView != null) {
                        if (partial instanceof Playlist playlist) updatePlaylistTrackCountView(playlist);
                        else if (partial instanceof Album album) updateAlbumTrackCountView(album);
                    }
                }))
                .thenAcceptAsync(result -> {
            callbacks.post(MuiModApi::postToUiThread, generation, () -> {
                if (loadingScope != MusicEntityCache.captureGeneration()) return;
                Collection<MusicDetail> musicDetails = result.musicDetails();
                MusicCollection musicCollection1 = result.musicCollection();
                this.musicCollection = musicCollection1;
                updateHeader(musicCollection1);
                detailComplete = true;
                currentCoverUrl = musicCollection1.getImageThumbnailUrl(imageSize);
                this.imageView.loadUrl(currentCoverUrl);
                if (musicTrackCountView != null) {
                    if (musicCollection1 instanceof Album album) {
                        updateAlbumTrackCountView(album);
                    } else if (musicCollection1 instanceof Playlist playlist){
                        updatePlaylistTrackCountView(playlist);
                    }
                }
                progressBar.setVisibility(View.GONE);
                tracksListView.updateItems(new ArrayList<>(musicDetails));
                updateTrackWindow();
                updateEditingEnabled();
                unregisterTracksSync();
                registerTracksSync(musicCollection1);
            });
        }, MusicHud.EXECUTOR).exceptionally(error -> {
            callbacks.post(MuiModApi::postToUiThread, generation, () -> {
                loadFailed = true;
                progressBar.setVisibility(View.GONE);
                ToastUtil.show(Toast.makeText(context,
                        I18n.get(MusicHud.MOD_ID + ".button.loadingError"), Toast.LENGTH_SHORT));
            });
            return null;
        });
    }

    private void unregisterTracksSync() {
        if (tracksSyncUnregister != null) {
            tracksSyncUnregister.unregister();
            tracksSyncUnregister = null;
        }
    }

    private void registerTracksSync(MusicCollection collection) {
        unregisterTracksSync();
        SequencedSet<MusicDetail> musicDetails = collection.getMusicDetails();
        if (!(musicDetails instanceof ObservableSequencedSet<MusicDetail> tracks)) {
            return;
        }
        long generation = viewGeneration;
        Consumer<MusicDetail> onChange = musicDetail -> scheduleTracksSync(generation);
        Unregister addUnregister = tracks.registerOnAdd(onChange);
        Unregister removeUnregister = tracks.registerOnRemove(onChange);
        tracksSyncUnregister = () -> {
            addUnregister.unregister();
            removeUnregister.unregister();
        };
    }

    private void scheduleTracksSync(long generation) {
        if (callbacks.isCurrent(generation) && syncPending.compareAndSet(false, true)) {
            callbacks.post(MuiModApi::postToUiThread, generation, () -> {
                syncPending.set(false);
                syncTracksView();
            });
        }
    }

    private void syncTracksView() {
        MusicCollection collection = musicCollection;
        if (collection == null) return;
        SequencedSet<MusicDetail> musicDetails = collection.getMusicDetails();
        if (musicDetails instanceof ObservableSequencedSet<MusicDetail> tracks) {
            syncTracksList(tracks);
        }
        String newCoverUrl = collection.getImageThumbnailUrl(imageSize);
        if (!Objects.equals(currentCoverUrl, newCoverUrl)) {
            currentCoverUrl = newCoverUrl;
            imageView.loadUrl(newCoverUrl);
        }
    }

    private void updateTrackWindow() {
        tracksListView.updateWindow(scrollView.getScrollY(), scrollView.getHeight());
    }

    private void syncTracksList(ObservableSequencedSet<MusicDetail> tracks) {
        tracksListView.updateItems(new ArrayList<>(tracks));
        updateTrackWindow();
    }

    private MusicListItem createTrackItem(Context context) {
        var musicLayout = new MusicListItem(context);
        musicLayout.setShowPusherInfo(false);
        InsetBackgroundFactory background = InsetBackgroundFactory.builder()
                .cornerRadius(dp(12))
                .inset(dp(1))
                .padding(new InsetBackgroundFactory.Padding(dp(4), dp(4), dp(4), dp(4))).build();
        background.applyBackgroundTo(musicLayout);

        musicLayout.setClickable(true);
        musicLayout.setOnClickListener(view -> {
            MusicDetail musicDetail = musicLayout.getMusicDetail();
            if (musicDetail == null || detached) return;
            String artistsName = musicDetail.getArtists().stream().map(Artist::getName).collect(Collectors.joining(" / "));
            MusicService.getInstance().sendPushMusicToQueue(musicDetail.withPlaybackSource(PlaybackSource.from(this.musicCollection, "MANUAL")));
            ToastUtil.show(Toast.makeText(context, I18n.get(MusicHud.MOD_ID + ".text.pushedMusicToPlaylist")
                    + "\n" + musicDetail.getName() + " - " + artistsName, Toast.LENGTH_SHORT));
        });
        if (editablePlaylist && musicCollection instanceof Playlist) {
            musicLayout.getButtonsLayout().addView(trackOrderButton(context, 90, ".button.moveUp",
                    v -> moveTrack(musicLayout, -1)), new LinearLayout.LayoutParams(dp(32), dp(40)));
            musicLayout.getButtonsLayout().addView(trackOrderButton(context, -90, ".button.moveDown",
                    v -> moveTrack(musicLayout, 1)), new LinearLayout.LayoutParams(dp(32), dp(40)));
            ImageButton remove = new ImageButton(context);
            remove.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            Image trash = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/trash_2.png");
            if (trash != null) {
                remove.setImageDrawable(new ScaledImageDrawable(context.getResources(), trash, dp(16), dp(16)));
            }
            remove.setTooltipText(I18n.get(MusicHud.MOD_ID + ".button.remove"));
            InsetBackgroundFactory.builder().cornerRadius(dp(4)).inset(dp(2))
                    .build().applyBackgroundTo(remove);
            remove.setOnClickListener(v -> removeTrack(musicLayout));
            musicLayout.getButtonsLayout().addView(remove, new LinearLayout.LayoutParams(dp(36), dp(40)));
            setItemEditingEnabled(musicLayout);
        }
        return musicLayout;
    }

    private ImageButton trackOrderButton(Context context, float rotation, String tooltipKey,
                                         OnClickListener listener) {
        ImageButton button = new ImageButton(context);
        String label = I18n.get(MusicHud.MOD_ID + tooltipKey);
        button.setTooltipText(label);
        button.setContentDescription(label);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        Image arrow = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/arrow_left.png");
        if (arrow != null) {
            button.setImageDrawable(new ScaledImageDrawable(context.getResources(), arrow, dp(16), dp(16)));
        }
        button.setRotation(rotation);
        InsetBackgroundFactory.builder().cornerRadius(dp(4)).inset(dp(2))
                .build().applyBackgroundTo(button);
        button.setOnClickListener(listener);
        return button;
    }

    private void moveTrack(MusicListItem item, int delta) {
        if (!(musicCollection instanceof Playlist playlist) || !canEditTracks()) return;
        List<MusicDetail> original = List.copyOf(playlist.getMusicDetails());
        int from = original.indexOf(item.getMusicDetail());
        int to = from + delta;
        List<MusicDetail> reordered = new ArrayList<>(original);
        if (from < 0 || to < 0 || to >= reordered.size()) return;
        MusicDetail moved = reordered.remove(from);
        reordered.add(to, moved);
        submitEdit(playlist, original, () -> {
                TuneWeaveClientService.getInstance().reorderPlaylistTracks(playlist, List.copyOf(reordered));
                return reordered;
            }, result -> {
                ObservableSequencedSet<MusicDetail> tracks = new ObservableSequencedSet<>();
                tracks.addAll(result);
                playlist.setTracks(tracks);
                unregisterTracksSync();
                registerTracksSync(playlist);
                syncTracksList(tracks);
        });
    }

    private void removeTrack(MusicListItem item) {
        if (!(musicCollection instanceof Playlist playlist) || !canEditTracks()) return;
        List<MusicDetail> original = List.copyOf(playlist.getMusicDetails());
        MusicDetail track = item.getMusicDetail();
        if (!original.contains(track)) return;
        submitEdit(playlist, original, () -> {
                TuneWeaveClientService.getInstance().modifyPlaylistTracks(playlist, track, false);
                return track;
            }, removed -> {
                if (playlist.getTracks().remove(removed))
                    playlist.setMusicTrackCount(Math.max(0, playlist.getMusicTrackCount() - 1));
                syncTracksList(playlist.getMusicDetails());
                updatePlaylistTrackCountView(playlist);
        });
    }

    private <T> void submitEdit(Playlist playlist, List<MusicDetail> original,
                                java.util.function.Supplier<T> request, Consumer<T> commit) {
        long generation = viewGeneration;
        Object scope = detailScope;
        try {
            var holder = new java.util.concurrent.atomic.AtomicReference<java.util.function.Supplier<T>>();
            MusicEntityCache.publish(scope, () -> holder.set(TuneWeaveClientService.getInstance().prepareRequest(request)));
            var prepared = holder.get();
            edits.submit(MusicHud.EXECUTOR, MuiModApi::postToUiThread,
                    () -> callbacks.isCurrent(generation) && scope == MusicEntityCache.captureGeneration(),
                    prepared, value -> {
                        if (musicCollection == playlist && original.equals(List.copyOf(playlist.getMusicDetails())))
                            MusicEntityCache.publish(scope, () -> commit.accept(value));
                        else refreshData(true);
                    }, error -> ToastUtil.show(Toast.makeText(getContext(),
                            I18n.get(MusicHud.MOD_ID + ".button.loadingError"), Toast.LENGTH_SHORT)), this::updateEditingEnabled);
            updateEditingEnabled();
        } catch (RuntimeException error) {
            updateEditingEnabled();
            ToastUtil.show(Toast.makeText(getContext(), I18n.get(MusicHud.MOD_ID + ".button.loadingError"), Toast.LENGTH_SHORT));
        }
    }

    private void updateEditingEnabled() {
        for (int i = 0; i < tracksListView.getChildCount(); i++)
            if (tracksListView.getChildAt(i) instanceof MusicListItem item) setItemEditingEnabled(item);
    }

    private void setItemEditingEnabled(MusicListItem item) {
        if (!editablePlaylist) return;
        var buttons = item.getButtonsLayout();
        for (int i = 0; i < buttons.getChildCount(); i++) buttons.getChildAt(i).setEnabled(canEditTracks());
    }

    private boolean canEditTracks() {
        return editablePlaylist && detailComplete && !detached && !edits.isBusy()
                && detailScope == MusicEntityCache.captureGeneration();
    }

    private String mappedAlbumType(String type) {
        return switch (type) {
            case "专辑" -> I18n.get(MusicHud.MOD_ID +".text.album.type.album");
            case "EP" -> I18n.get(MusicHud.MOD_ID + ".text.album.type.ep");
            case "Single" -> I18n.get(MusicHud.MOD_ID + ".text.album.type.single");
            case "精选集" -> I18n.get(MusicHud.MOD_ID + ".text.album.type.compilation");
            default -> type;
        };
    }
}
