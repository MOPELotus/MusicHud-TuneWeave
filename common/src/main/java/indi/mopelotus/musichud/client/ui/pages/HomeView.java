package indi.mopelotus.musichud.client.ui.pages;

import icyllis.modernui.animation.LayoutTransition;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.drawable.Drawable;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.QueueItem;
import indi.mopelotus.musichud.client.audio.NowPlayingInfo;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveVideo;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveVideoCreator;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.FlexWrapLayout;
import indi.mopelotus.musichud.client.ui.components.MusicCollectionCard;
import indi.mopelotus.musichud.client.ui.components.MusicListItem;
import indi.mopelotus.musichud.client.ui.components.StaggeredLyricScrollView;
import indi.mopelotus.musichud.client.ui.components.UrlImageView;
import indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable;
import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.interfaces.Unregister;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public class HomeView extends LinearLayout {
    private static final Logger LOGGER = MusicHud.getLogger(HomeView.class);
    private static final MusicService musicService = MusicService.getInstance();
    private static final ClientConfig clientConfig = ClientConfig.getInstance();
    private static final ActiveViewReference<HomeView> ACTIVE_INSTANCE = new ActiveViewReference<>();
    private final Set<MusicCollection> serverIdlePlaySources = musicService.getIdlePlaySourceState().external().getSources();
    private final Set<MusicCollection> clientIdlePlaySources = musicService.getIdlePlaySourceState().local().getSources();
    private final Map<MusicCollection, MusicCollectionCard> idlePlaySourceCardMap = new ConcurrentHashMap<>();
    @Getter
    private StaggeredLyricScrollView staggeredLyricScrollView;
    private LinearLayout queuePane;
    private LinearLayout videoPreview;
    private UrlImageView videoPreviewImage;
    private TextView videoPreviewTitle;
    private TextView videoPreviewMeta;
    private TextView videoPreviewDescription;
    private volatile String videoPreviewKey = "";
    private long videoPreviewRequestGeneration;
    private MusicListItem nextToPlayItem;
    private indi.mopelotus.musichud.client.ui.components.PlaybackSourceLink currentSourceLink;
    private TextView nextToPlayTitle;
    private TextView queueTitle;
    private LinearLayout playQueueListView;
    private LinearLayout clientIdlePlaySourceView;
    private LinearLayout serverIdlePlaySourceView;
    private FlexWrapLayout clientIdlePlaySourceCardsList;
    private final Consumer<MusicCollection> localAddListener = collection -> {
        postHome(() -> {
            if (clientIdlePlaySources.contains(collection) && !idlePlaySourceCardMap.containsKey(collection)) {
                addIdlePlaySourceTo(collection, getContext(), clientIdlePlaySourceCardsList);
                checkIdlePlaySources(clientIdlePlaySources, clientIdlePlaySourceView);
            }
        });
    };
    private final Consumer<MusicCollection> localRemoveListener = collection -> {
        postHome(() -> {
            if (clientIdlePlaySources.contains(collection)) return;
            MusicCollectionCard view = idlePlaySourceCardMap.remove(collection);
            if (view != null) {
                clientIdlePlaySourceCardsList.removeView(view);
                checkIdlePlaySources(clientIdlePlaySources, clientIdlePlaySourceView);
            }
        });
    };
    private FlexWrapLayout serverIdlePlaySourceCardsList;
    private final Consumer<MusicCollection> serverRemoveListener = collection -> {
        postHome(() -> {
            if (serverIdlePlaySources.contains(collection)) return;
            MusicCollectionCard view = idlePlaySourceCardMap.remove(collection);
            if (view != null) {
                serverIdlePlaySourceCardsList.removeView(view);
                checkIdlePlaySources(serverIdlePlaySources, serverIdlePlaySourceView);
            }
        });
    };
    private Consumer<QueueItem> musicQueuePushListener;
    private BiConsumer<Integer, QueueItem> musicQueueRemoveListener;
    private Unregister localAddRegister;
    private Unregister localRemoveRegister;
    private Unregister serverAddRegister;
    private Unregister serverRemoveRegister;
    private Unregister playbackStateRegister;
    private final Consumer<NowPlayingInfo.PlaybackSnapshot> playbackStateListener = snapshot ->
            postHome(() -> {
                if (ACTIVE_INSTANCE.isCurrent(this) && NowPlayingInfo.getInstance().snapshot() == snapshot) {
                    applyPlaybackSnapshot(snapshot);
                }
            });
    private LocalPlayer localPlayer = Minecraft.getInstance().player;
    private final Consumer<MusicCollection> serverAddListener = collection -> {
        postHome(() -> {
            if ((localPlayer != null && !java.util.Objects.equals(collection.getPusherInfo().getPlayerUUID(), localPlayer.getUUID()))
                    && serverIdlePlaySources.contains(collection) && !idlePlaySourceCardMap.containsKey(collection)) {
                addIdlePlaySourceTo(collection, getContext(), serverIdlePlaySourceCardsList);
                checkIdlePlaySources(serverIdlePlaySources, serverIdlePlaySourceView);
            }
        });
    };

    public HomeView(Context context) {
        super(context);
        refresh();
    }

    public static HomeView getInstance() {
        return ACTIVE_INSTANCE.get();
    }

    private final indi.mopelotus.musichud.client.ui.CallbackGeneration callbackGeneration = new indi.mopelotus.musichud.client.ui.CallbackGeneration();
    private volatile long notificationGeneration;
    private void postHome(Runnable action) {
        long ticket = notificationGeneration;
        callbackGeneration.post(MuiModApi::postToUiThread, ticket, () -> {
            if (ACTIVE_INSTANCE.isCurrent(this)) action.run();
        });
    }

    public void refresh() {
        notificationGeneration = callbackGeneration.next();
        localPlayer = Minecraft.getInstance().player;
        ACTIVE_INSTANCE.attach(this);
        releaseSubscriptions();
        Context context = getContext();
        removeAllViews();
        currentSourceLink = new indi.mopelotus.musichud.client.ui.components.PlaybackSourceLink(getContext());
        idlePlaySourceCardMap.clear();

        boolean enabled = clientConfig.getEnable();
        if (MusicHud.getConnectStatus() != MusicHud.ConnectStatus.CONNECTED && !ClientConfig.getInstance().getEnableIsolatedMode() || !enabled) {
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            addView(currentSourceLink);
            TextView textView = Theme.getNotificationTextView(context, enabled);
            addView(textView);
            return;
        }

        setOrientation(VERTICAL);
        addView(currentSourceLink, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        LinearLayout contentRow = new LinearLayout(context);
        contentRow.setOrientation(HORIZONTAL);
        addView(contentRow, new LayoutParams(MATCH_PARENT, 0, 1));
        {
            LinearLayout lyricsView = new LinearLayout(context);
            lyricsView.setOrientation(VERTICAL);
            LayoutParams lyricsViewParams = new LayoutParams(0, MATCH_PARENT, 3);
            contentRow.addView(lyricsView, lyricsViewParams);

            FrameLayout playbackContent = new FrameLayout(context);
            lyricsView.addView(playbackContent, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

            staggeredLyricScrollView = new StaggeredLyricScrollView(context);
            playbackContent.addView(staggeredLyricScrollView,
                    new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

            videoPreview = new LinearLayout(context);
            videoPreview.setOrientation(VERTICAL);
            videoPreview.setGravity(Gravity.CENTER);
            videoPreview.setVisibility(GONE);

            videoPreviewImage = new UrlImageView(context);
            videoPreviewImage.setSquareCrop(false);
            videoPreviewImage.setAspectRatio(16f / 9f);
            videoPreviewImage.setCornerRadius(dp(8));
            LinearLayout.LayoutParams previewImageParams = new LinearLayout.LayoutParams(dp(400), WRAP_CONTENT);
            previewImageParams.setMargins(dp(32), dp(32), dp(32), dp(16));
            videoPreview.addView(videoPreviewImage, previewImageParams);

            videoPreviewTitle = new TextView(context);
            videoPreviewTitle.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            videoPreviewTitle.setTextSize(Theme.TEXT_SIZE_LARGE);
            videoPreviewTitle.setGravity(Gravity.CENTER);
            videoPreviewTitle.setTextAlignment(TEXT_ALIGNMENT_CENTER);
            videoPreviewTitle.setMaxLines(2);
            LinearLayout.LayoutParams previewTitleParams = new LinearLayout.LayoutParams(dp(400), WRAP_CONTENT);
            previewTitleParams.setMargins(dp(32), 0, dp(32), dp(4));
            videoPreview.addView(videoPreviewTitle, previewTitleParams);

            videoPreviewMeta = new TextView(context);
            videoPreviewMeta.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            videoPreviewMeta.setTextSize(Theme.TEXT_SIZE_NORMAL);
            videoPreviewMeta.setGravity(Gravity.CENTER);
            videoPreviewMeta.setTextAlignment(TEXT_ALIGNMENT_CENTER);
            LinearLayout.LayoutParams previewMetaParams = new LinearLayout.LayoutParams(dp(400), WRAP_CONTENT);
            previewMetaParams.setMargins(dp(32), 0, dp(32), dp(8));
            videoPreview.addView(videoPreviewMeta, previewMetaParams);

            videoPreviewDescription = new TextView(context);
            videoPreviewDescription.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            videoPreviewDescription.setTextSize(Theme.TEXT_SIZE_NORMAL);
            videoPreviewDescription.setGravity(Gravity.CENTER);
            videoPreviewDescription.setTextAlignment(TEXT_ALIGNMENT_CENTER);
            videoPreviewDescription.setMaxLines(8);
            LinearLayout.LayoutParams previewDescriptionParams = new LinearLayout.LayoutParams(dp(400), WRAP_CONTENT);
            previewDescriptionParams.setMargins(dp(32), 0, dp(32), dp(32));
            videoPreview.addView(videoPreviewDescription, previewDescriptionParams);

            playbackContent.addView(videoPreview,
                    new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        }
        {
            LinearLayout queueView = new LinearLayout(context);
            queuePane = queueView;
            queueView.setOrientation(VERTICAL);
            queueView.setVisibility(videoPreview != null && videoPreview.getVisibility() == VISIBLE ? GONE : VISIBLE);
            LayoutParams queueViewParams = new LayoutParams(0, MATCH_PARENT, 2);
            contentRow.addView(queueView, queueViewParams);

            var scrollView = new ScrollView(context);
            scrollView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
            scrollView.setFillViewport(true);
            queueView.addView(scrollView, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

            LinearLayout scrollViewContainer = new LinearLayout(context);
            scrollViewContainer.setOrientation(VERTICAL);
            scrollView.addView(scrollViewContainer, new LayoutParams(MATCH_PARENT, MATCH_PARENT));
            LayoutTransition transition1 = new LayoutTransition();
            transition1.enableTransitionType(LayoutTransition.CHANGING);
            scrollViewContainer.setLayoutTransition(transition1);

            nextToPlayTitle = new TextView(context);
            nextToPlayTitle.setVisibility(GONE);
            nextToPlayTitle.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            nextToPlayTitle.setText(I18n.get(MusicHud.MOD_ID + ".text.nextToPlay"));
            LayoutParams nextToPlayTitleParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            nextToPlayTitleParams.setMargins(0, dp(32), 0, dp(16));
            scrollViewContainer.addView(nextToPlayTitle, nextToPlayTitleParams);

            nextToPlayItem = new MusicListItem(context);
            nextToPlayItem.setVisibility(GONE);
            scrollViewContainer.addView(nextToPlayItem, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

            queueTitle = new TextView(context);
            queueTitle.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            queueTitle.setText(I18n.get(MusicHud.MOD_ID + ".text.playQueue"));
            LayoutParams queueTitleParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            queueTitleParams.setMargins(0, dp(32), 0, dp(16));
            scrollViewContainer.addView(queueTitle, queueTitleParams);

            playQueueListView = new LinearLayout(context);
            playQueueListView.setOrientation(VERTICAL);
            LayoutParams queueViewParams1 = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            playQueueListView.setMinimumHeight(dp(256));
            LayoutTransition transition = new LayoutTransition();
            transition.enableTransitionType(LayoutTransition.CHANGING);
            playQueueListView.setLayoutTransition(transition);
            scrollViewContainer.addView(playQueueListView, queueViewParams1);


            clientIdlePlaySourceView = new LinearLayout(context);
            clientIdlePlaySourceView.setVisibility(GONE);
            clientIdlePlaySourceView.setOrientation(VERTICAL);
            clientIdlePlaySourceView.setLayoutParams(new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

            TextView clientIdlePlaySourceViewTitle = new TextView(context);
            clientIdlePlaySourceViewTitle.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            clientIdlePlaySourceViewTitle.setTextSize(Theme.TEXT_SIZE_LARGE);
            clientIdlePlaySourceViewTitle.setText(I18n.get(MusicHud.MOD_ID + ".text.idlePlaySources"));
            LayoutParams params2 = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            params2.setMargins(0, dp(32), 0, 0);
            clientIdlePlaySourceView.addView(clientIdlePlaySourceViewTitle, params2);

            TextView idlePlaySourceViewDescription = new TextView(context);
            idlePlaySourceViewDescription.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            idlePlaySourceViewDescription.setTextSize(Theme.TEXT_SIZE_NORMAL);
            idlePlaySourceViewDescription.setText(I18n.get(MusicHud.MOD_ID + ".text.idlePlaySourcesDescription"));
            clientIdlePlaySourceView.addView(idlePlaySourceViewDescription, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

            clientIdlePlaySourceCardsList = new FlexWrapLayout(context);
            LayoutParams params4 = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            params4.setMargins(0, dp(16), 0, 0);
            clientIdlePlaySourceView.addView(clientIdlePlaySourceCardsList, params4);
            scrollViewContainer.addView(clientIdlePlaySourceView, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));


            serverIdlePlaySourceView = new LinearLayout(context);
            serverIdlePlaySourceView.setVisibility(GONE);
            serverIdlePlaySourceView.setOrientation(VERTICAL);
            serverIdlePlaySourceView.setLayoutParams(new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

            TextView serverIdlePlaySourceViewTitle = new TextView(context);
            serverIdlePlaySourceViewTitle.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            serverIdlePlaySourceViewTitle.setTextSize(Theme.TEXT_SIZE_LARGE);
            serverIdlePlaySourceViewTitle.setText(I18n.get(MusicHud.MOD_ID + ".text.othersIdlePlaySources"));
            LayoutParams params5 = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            params5.setMargins(0, dp(32), 0, 0);
            serverIdlePlaySourceView.addView(serverIdlePlaySourceViewTitle, params5);

            TextView idlePlaySourceViewDescription1 = new TextView(context);
            idlePlaySourceViewDescription1.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            idlePlaySourceViewDescription1.setTextSize(Theme.TEXT_SIZE_NORMAL);
            idlePlaySourceViewDescription1.setText(I18n.get(MusicHud.MOD_ID + ".text.idlePlaySourcesDescription"));
            serverIdlePlaySourceView.addView(idlePlaySourceViewDescription1, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

            serverIdlePlaySourceCardsList = new FlexWrapLayout(context);
            LayoutParams params6 = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            params6.setMargins(0, dp(16), 0, 0);
            serverIdlePlaySourceView.addView(serverIdlePlaySourceCardsList, params6);
            scrollViewContainer.addView(serverIdlePlaySourceView, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

            localPlayer = Minecraft.getInstance().player;

            clientIdlePlaySources.forEach(collection -> {
                if (!idlePlaySourceCardMap.containsKey(collection)) {
                    MusicCollectionCard child = new MusicCollectionCard(context, collection);
                    clientIdlePlaySourceCardsList.addView(child);
                    idlePlaySourceCardMap.put(collection, child);
                }
            });
            serverIdlePlaySources.forEach(collection -> {
                if (localPlayer != null && !collection.getPusherInfo().getPlayerUUID().equals(localPlayer.getUUID()) && !idlePlaySourceCardMap.containsKey(collection)) {
                    MusicCollectionCard child = new MusicCollectionCard(context, collection);
                    serverIdlePlaySourceCardsList.addView(child);
                    idlePlaySourceCardMap.put(collection, child);
                }
            });
            checkIdlePlaySources(clientIdlePlaySources, clientIdlePlaySourceView);
            checkIdlePlaySources(serverIdlePlaySources, serverIdlePlaySourceView);
            checkQueue(musicService.getMusicQueue());

            Queue<QueueItem> queue = musicService.getMusicQueue();

            localAddRegister = musicService.getIdlePlaySourceState().local().onAdd(localAddListener);
            localRemoveRegister = musicService.getIdlePlaySourceState().local().onRemove(localRemoveListener);
            serverAddRegister = musicService.getIdlePlaySourceState().external().onAdd(serverAddListener);
            serverRemoveRegister = musicService.getIdlePlaySourceState().external().onRemove(serverRemoveListener);

            playQueueListView.removeAllViews();

            for (QueueItem item : queue) {
                addMusicQueueItem(item, playQueueListView);
            }

            musicQueuePushListener = item -> {
                postHome(() -> {
                    if (queue.stream().anyMatch(value -> value.queueUniqueID().equals(item.queueUniqueID()))
                            && findQueueItem(item) == null) addMusicQueueItem(item, playQueueListView);
                    checkQueue(queue);
                });
            };
            musicQueueRemoveListener = (removeIndex, item) -> {
                postHome(() -> {
                    View row = findQueueItem(item);
                    if (row != null && queue.stream().noneMatch(value -> value.queueUniqueID().equals(item.queueUniqueID())))
                        playQueueListView.removeView(row);
                    checkQueue(queue);
                });
            };
            musicService.getMusicQueuePushListeners().add(musicQueuePushListener);
            musicService.getMusicQueueRemoveListeners().add(musicQueueRemoveListener);
        }
        subscribePlaybackState();
        applyPlaybackSnapshot(NowPlayingInfo.getInstance().snapshot());
    }

    private void subscribePlaybackState() {
        if (playbackStateRegister == null) {
            playbackStateRegister = NowPlayingInfo.getInstance()
                    .addPlaybackStateListener(playbackStateListener);
        }
    }

    private void releaseSubscriptions() {
        if (localAddRegister != null) {
            localAddRegister.unregister();
            localAddRegister = null;
        }
        if (localRemoveRegister != null) {
            localRemoveRegister.unregister();
            localRemoveRegister = null;
        }
        if (serverAddRegister != null) {
            serverAddRegister.unregister();
            serverAddRegister = null;
        }
        if (serverRemoveRegister != null) {
            serverRemoveRegister.unregister();
            serverRemoveRegister = null;
        }
        if (musicQueuePushListener != null) {
            musicService.getMusicQueuePushListeners().remove(musicQueuePushListener);
            musicQueuePushListener = null;
        }
        if (musicQueueRemoveListener != null) {
            musicService.getMusicQueueRemoveListeners().remove(musicQueueRemoveListener);
            musicQueueRemoveListener = null;
        }
        if (playbackStateRegister != null) {
            playbackStateRegister.unregister();
            playbackStateRegister = null;
        }
    }

    private void addIdlePlaySourceTo(MusicCollection idlePlaySource, Context context, FlexWrapLayout targetView) {
        MusicCollectionCard child = new MusicCollectionCard(context, idlePlaySource);
        targetView.addView(child);
        idlePlaySourceCardMap.put(idlePlaySource, child);
    }

    private void checkQueue(Queue<QueueItem> queue) {
        if (queue.isEmpty()) {
            queueTitle.setVisibility(View.GONE);
            playQueueListView.setVisibility(View.GONE);
            checkNextToPlay(NowPlayingInfo.getInstance().getNextToPlayIdleMusicDetail());
        } else {
            queueTitle.setVisibility(View.VISIBLE);
            playQueueListView.setVisibility(View.VISIBLE);
            checkNextToPlay(queue.peek().musicDetail());
        }
    }

    private void checkIdlePlaySources(Set<MusicCollection> idlePlaySources, View targetView) {
        if (idlePlaySources.isEmpty()) {
            targetView.setVisibility(View.GONE);
        } else {
            targetView.setVisibility(View.VISIBLE);
        }
        checkQueue(MusicService.getInstance().getMusicQueue());
    }

    private void checkNextToPlay(MusicDetail nextIdle) {
        MusicService musicService = MusicService.getInstance();
        Queue<QueueItem> musicQueue = musicService.getMusicQueue();
        boolean hasIdlePlaySources = !musicService.getIdlePlaySourceState().local().getSources().isEmpty() || !musicService.getIdlePlaySourceState().external().getSources().isEmpty();
        MusicDetail next = hasIdlePlaySources ? nextIdle : null;
        if (musicQueue.isEmpty() && next != null && !next.equals(MusicDetail.NONE)) {
            nextToPlayTitle.setVisibility(VISIBLE);
            nextToPlayItem.setVisibility(VISIBLE);
            nextToPlayItem.bindData(next);
        } else {
            nextToPlayTitle.setVisibility(GONE);
            nextToPlayItem.setVisibility(GONE);
        }
    }

    private void addMusicQueueItem(QueueItem item, LinearLayout playQueueView) {
        MusicDetail musicDetail = item.musicDetail();
        var musicListItem = new MusicListItem(getContext());
        musicListItem.setTag(item.queueUniqueID());
        musicListItem.bindData(musicDetail);
        LayoutParams layoutParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT);
        layoutParams.setMargins(0, 0, 0, dp(16));

        if (Minecraft.getInstance().player != null && musicDetail.getPusherInfo().getPlayerUUID().equals(Minecraft.getInstance().player.getUUID())) {
            ImageButton removeButton = new ImageButton(getContext());
            Image removeIcon = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/trash_2.png");
            removeButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            removeButton.setImageDrawable(new ScaledImageDrawable(getContext().getResources(), removeIcon, dp(16), dp(16)));
            Drawable background = ButtonInsetBackgroundFactory.builder()
                    .inset(dp(2))
                    .cornerRadius(dp(4))
                    .build().newBackgroundDrawable();
            removeButton.setBackground(background);
            removeButton.setOnClickListener(v -> {
                MusicService.getInstance().sendRemoveMusicFromQueue(playQueueView.indexOfChild(musicListItem), item);
            });
            musicListItem.getButtonsLayout().addView(removeButton, new LinearLayout.LayoutParams(dp(40), dp(40), 0));
        }
        musicListItem.setLayoutParams(layoutParams);
        playQueueView.addView(musicListItem, layoutParams);
    }

    private void applyPlaybackSnapshot(NowPlayingInfo.PlaybackSnapshot snapshot) {
        if (currentSourceLink != null) currentSourceLink.bind(snapshot.musicDetail() == null
                ? indi.mopelotus.musichud.beans.music.PlaybackSource.NONE : snapshot.musicDetail().getPlaybackSource());
        if (staggeredLyricScrollView == null) {
            return;
        }
        updatePlaybackContent(snapshot.musicDetail(), snapshot.lyrics());
        staggeredLyricScrollView.switchLyrics(snapshot.musicDetail(), snapshot.lyrics());
        checkNextToPlay(snapshot.nextToPlay());
    }

    private View findQueueItem(QueueItem item) {
        if (playQueueListView == null) return null;
        for (int i = 0; i < playQueueListView.getChildCount(); i++) {
            View row = playQueueListView.getChildAt(i);
            if (item.queueUniqueID().equals(row.getTag())) return row;
        }
        return null;
    }

    private void updatePlaybackContent(MusicDetail musicDetail, Collection<LyricLine> lyricLines) {
        if (staggeredLyricScrollView == null || videoPreview == null) return;
        boolean video = musicDetail != null && musicDetail != MusicDetail.NONE
                && "video".equals(musicDetail.getSourceKind());
        boolean hasVideoLyrics = lyricLines != null && !lyricLines.isEmpty();
        boolean showVideoPreview = video && !hasVideoLyrics;
        staggeredLyricScrollView.setVisibility(showVideoPreview ? GONE : VISIBLE);
        videoPreview.setVisibility(showVideoPreview ? VISIBLE : GONE);
        if (queuePane != null) queuePane.setVisibility(showVideoPreview ? GONE : VISIBLE);
        long requestGeneration = ++videoPreviewRequestGeneration;
        if (!showVideoPreview) {
            videoPreviewKey = "";
            return;
        }

        String previewKey = videoPreviewKey(musicDetail);
        videoPreviewKey = previewKey;

        videoPreviewImage.loadUrl(musicDetail.getAlbum().getPicUrl());
        videoPreviewTitle.setText(musicDetail.getName());
        String artists = musicDetail.getArtists().stream()
                .map(artist -> artist.getName())
                .filter(name -> name != null && !name.isBlank())
                .reduce((left, right) -> left + " / " + right)
                .orElse("Bilibili");
        videoPreviewMeta.setText(artists);
        videoPreviewDescription.setText(I18n.get(MusicHud.MOD_ID + ".text.video.loading"));
        MusicHud.EXECUTOR.execute(() -> {
            try {
                TuneWeaveVideo videoInfo = TuneWeaveClientService.getInstance()
                        .loadVideoDetail(musicDetail);
                postHome(() -> applyVideoPreviewInfo(
                        previewKey, requestGeneration, musicDetail, videoInfo));
            } catch (RuntimeException error) {
                LOGGER.warn("Failed to load Bilibili video preview details for {}", previewKey, error);
                postHome(() -> applyVideoPreviewError(previewKey, requestGeneration));
            }
        });
    }

    private void applyVideoPreviewInfo(String expectedKey, long requestGeneration,
                                       MusicDetail expectedMusic, TuneWeaveVideo videoInfo) {
        if (!isCurrentVideoPreview(expectedKey, requestGeneration)) return;
        videoPreviewTitle.setText(videoInfo.title());
        String creators = videoInfo.creators().isEmpty()
                ? expectedMusic.getArtists().stream().map(artist -> artist.getName())
                .filter(name -> name != null && !name.isBlank())
                .reduce((left, right) -> left + " / " + right).orElse("Bilibili")
                : videoInfo.creators().stream()
                .map(TuneWeaveVideoCreator::name)
                .reduce((left, right) -> left + " / " + right).orElse("Bilibili");
        String publishedAt = formatPublishedAt(videoInfo.publishedAt());
        videoPreviewMeta.setText(publishedAt.isBlank() ? creators : creators + "  ·  " + publishedAt);
        videoPreviewDescription.setText(videoInfo.description().isBlank()
                ? I18n.get(MusicHud.MOD_ID + ".text.video.noDescription") : videoInfo.description());
    }

    private void applyVideoPreviewError(String expectedKey, long requestGeneration) {
        if (!isCurrentVideoPreview(expectedKey, requestGeneration)) return;
        videoPreviewDescription.setText(I18n.get(MusicHud.MOD_ID + ".text.video.detailsUnavailable"));
    }

    private boolean isCurrentVideoPreview(String expectedKey, long requestGeneration) {
        return videoPreviewRequestGeneration == requestGeneration
                && videoPreviewKey.equals(expectedKey)
                && videoPreview.getVisibility() == VISIBLE;
    }

    private static String videoPreviewKey(MusicDetail musicDetail) {
        return musicDetail.getSourceRef() + "|" + musicDetail.getSourcePartRef();
    }

    private static String formatPublishedAt(String publishedAt) {
        if (publishedAt == null || publishedAt.isBlank()) return "";
        int timeSeparator = publishedAt.indexOf('T');
        return timeSeparator > 0 ? publishedAt.substring(0, timeSeparator) : publishedAt;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ACTIVE_INSTANCE.attach(this);
        subscribePlaybackState();
        applyPlaybackSnapshot(NowPlayingInfo.getInstance().snapshot());
    }

    @Override
    protected void onDetachedFromWindow() {
        notificationGeneration = callbackGeneration.next();
        super.onDetachedFromWindow();
        releaseSubscriptions();
        ACTIVE_INSTANCE.detach(this);
    }
}
