package indi.mopelotus.musichud.client.ui.pages.account;

import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.ImageButton;
import icyllis.modernui.widget.ImageView;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.text.SpannableString;
import icyllis.modernui.text.Spanned;
import icyllis.modernui.graphics.drawable.InsetDrawable;
import indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ProgressBar;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.music.UserCategoryPlaylists;
import indi.mopelotus.musichud.client.services.LoginService;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveSession;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.CallbackGeneration;
import indi.mopelotus.musichud.client.ui.components.ArtistCard;
import indi.mopelotus.musichud.client.ui.components.FlexWrapLayout;
import indi.mopelotus.musichud.client.ui.components.MusicCollectionCard;
import indi.mopelotus.musichud.client.ui.components.PlatformSelector;
import indi.mopelotus.musichud.client.ui.components.RouterContainer;
import indi.mopelotus.musichud.client.ui.pages.CloudView;
import indi.mopelotus.musichud.client.ui.pages.PodcastRadioView;
import indi.mopelotus.musichud.client.ui.pages.UniPlaylistView;
import indi.mopelotus.musichud.client.ui.components.UrlImageView;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import indi.mopelotus.musichud.interfaces.IClientLoginService;
import indi.mopelotus.musichud.interfaces.Unregister;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;
import lombok.Getter;
import net.minecraft.client.resources.language.I18n;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public class AccountView extends LinearLayout {
    @Getter
    private static AccountView instance;
    private final IClientLoginService clientLoginService = LoginService.getInstance();
    private final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();
    private TuneWeavePlatform selectedPlatform = tuneWeave.defaultPlatform();
    private boolean showingUniPlaylists;
    private final CallbackGeneration callbacks = new CallbackGeneration();
    private final Map<ElementKey, View> elementMap = new HashMap<>();
    private FlexWrapLayout myPlaylistCards;
    private FlexWrapLayout mySubscribedPlaylistCards;
    private FlexWrapLayout albumCards;
    private FlexWrapLayout artistCards;
    private LinearLayout myPlaylistsContent;
    private LinearLayout mySubscribedPlaylistsContent;
    private LinearLayout mySubscribedAlbumsContent;
    private LinearLayout mySubscribedArtistsContent;
    private Unregister playlistAddRegister;
    private Unregister playlistRemoveRegister;
    private Unregister albumAddRegister;
    private Unregister albumRemoveRegister;
    private Unregister artistAddRegister;
    private Unregister artistRemoveRegister;
    private void createPlaylistCard(Playlist playlist, long generation) {
        post(generation, () -> {
            if (!isAttachedToWindow()) {
                return;
            }
            long id = playlist.getId();
            elementMap.computeIfAbsent(new ElementKey(Playlist.class, id), (key) -> {
                MusicCollectionCard card = new MusicCollectionCard(getContext(), playlist);
                card.setTag(id);
                mySubscribedPlaylistCards.addView(card);
                mySubscribedPlaylistsContent.setVisibility(VISIBLE);
                return card;
            });
        });
    }
    private void createAlbumCard(Album album, long generation) {
        post(generation, () -> {
            if (!isAttachedToWindow()) {
                return;
            }
            long id = album.getId();
            elementMap.computeIfAbsent(new ElementKey(Album.class, id), (key) -> {
                MusicCollectionCard card = new MusicCollectionCard(getContext(), album);
                card.setTag(id);
                albumCards.addView(card);
                mySubscribedAlbumsContent.setVisibility(VISIBLE);
                return card;
            });
        });
    }
    private void createArtistCard(Artist artist, long generation) {
        post(generation, () -> {
            if (!isAttachedToWindow()) {
                return;
            }
            long id = artist.getId();
            elementMap.computeIfAbsent(new ElementKey(Artist.class, id), (key) -> {
                ArtistCard artistCard = new ArtistCard(getContext());
                artistCard.setTag(artist.getId());
                artistCard.bindData(artist);
                artistCards.addView(artistCard);
                mySubscribedArtistsContent.setVisibility(VISIBLE);
                return artistCard;
            });
        });
    }

    public AccountView(Context context) {
        super(context);
        if (!tuneWeave.hasCredential(selectedPlatform)) {
            for (TuneWeavePlatform platform : TuneWeavePlatform.values()) {
                if (tuneWeave.hasCredential(platform)) {
                    selectedPlatform = platform;
                    break;
                }
            }
        }
//        refresh(false);
        instance = this;
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                instance = AccountView.this;
                refresh(false);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                callbacks.next();
                unregisterCollectionListeners();
                if (instance == AccountView.this) instance = null;
            }
        });
    }

    private void buildCard(Context context, InsetBackgroundFactory background, ViewGroup target,
                           String key, String icon, View.OnClickListener onClick) {
        Button button = new Button(context);
        button.setTextSize(Theme.TEXT_SIZE_LARGE);
        SpannableString label = new SpannableString("  " + I18n.get(MusicHud.MOD_ID + key));
        Image image = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/" + icon);
        if (image != null) label.setSpan(ImageUtils.getIconSpan(image), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(label);
        background.applyBackgroundTo(button);
        button.setOnClickListener(onClick);
        target.addView(button, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
    }

    private void buildIcon(Context context, InsetBackgroundFactory background, ViewGroup target,
                           String key, String icon, View.OnClickListener onClick) {
        ImageButton button = new ImageButton(context);
        background.applyBackgroundTo(button);
        button.setTooltipText(I18n.get(MusicHud.MOD_ID + key));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        Image image = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/" + icon);
        if (image != null) button.setImageDrawable(new InsetDrawable(
                new ScaledImageDrawable(context.getResources(), image, dp(12), dp(16)), dp(3)));
        button.setOnClickListener(onClick);
        target.addView(button, new LayoutParams(WRAP_CONTENT, dp(40)));
    }

    private void unregisterCollectionListeners() {
        if (playlistAddRegister != null) {
            playlistAddRegister.unregister();
            playlistAddRegister = null;
        }
        if (playlistRemoveRegister != null) {
            playlistRemoveRegister.unregister();
            playlistRemoveRegister = null;
        }
        if (albumAddRegister != null) {
            albumAddRegister.unregister();
            albumAddRegister = null;
        }
        if (albumRemoveRegister != null) {
            albumRemoveRegister.unregister();
            albumRemoveRegister = null;
        }
        if (artistAddRegister != null) {
            artistAddRegister.unregister();
            artistAddRegister = null;
        }
        if (artistRemoveRegister != null) {
            artistRemoveRegister.unregister();
            artistRemoveRegister = null;
        }
    }

    public void refresh(boolean ignoreCache) {
        long generation = callbacks.next();
        unregisterCollectionListeners();
        removeAllViews();
        elementMap.clear();
        setOrientation(LinearLayout.VERTICAL);
        setLayoutParams(new LayoutParams(MATCH_PARENT, MATCH_PARENT));
        Context context = getContext();

        TuneWeavePlatform activePlatform = tuneWeave.defaultPlatform();
        if (!tuneWeave.hasCredential(selectedPlatform) && tuneWeave.hasCredential(activePlatform)) {
            selectedPlatform = activePlatform;
        }

        LinearLayout platformTabs = new LinearLayout(context);
        platformTabs.setOrientation(LinearLayout.HORIZONTAL);
        platformTabs.setGravity(Gravity.CENTER);
        PlatformSelector platformSelector = new PlatformSelector(context, TuneWeavePlatform.values());
        platformSelector.setSelectedPlatform(selectedPlatform);
        platformSelector.setOnPlatformSelectedListener(platform -> {
            showingUniPlaylists = false;
            selectedPlatform = platform;
            tuneWeave.setDefaultPlatform(platform);
            LoginService.getInstance().switchTuneWeavePlatform(platform);
            refresh(false);
        });
        Image uniIcon = ImageUtils.getImageFromResource(
                "/assets/musichud_tuneweave/textures/gui/icons/list_music.png");
        platformSelector.addAuxiliarySegment(uniIcon,
                I18n.get(MusicHud.MOD_ID + ".text.page.uniPlaylists"), showingUniPlaylists, () -> {
                    showingUniPlaylists = true;
                    refresh(false);
                });
        platformTabs.addView(platformSelector, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        LayoutParams platformTabsParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        platformTabsParams.setMargins(0, dp(16), 0, dp(8));
        addView(platformTabs, platformTabsParams);

        tuneWeave.setDefaultPlatform(selectedPlatform);
        if (showingUniPlaylists) {
            addView(new UniPlaylistView(context), new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            return;
        }
        if (!tuneWeave.hasCredential(selectedPlatform)) {
            LoginView loginView = new LoginView(context);
            addView(loginView, new LayoutParams(MATCH_PARENT, 0, 1));
            return;
        }

        TuneWeaveSession sessionProfile = tuneWeave.cachedSession(selectedPlatform);
        String avatarUrl = sessionProfile != null && sessionProfile.avatarUrl() != null
                && !sessionProfile.avatarUrl().isBlank()
                ? sessionProfile.avatarUrl() : MusicHud.ICON_BASE64;
        String displayName = sessionProfile != null && sessionProfile.nickname() != null
                && !sessionProfile.nickname().isBlank()
                ? sessionProfile.nickname()
                : I18n.get(MusicHud.MOD_ID + ".platform." + selectedPlatform.apiName());
        String displayId = sessionProfile != null && sessionProfile.userId() != null
                && !sessionProfile.userId().isBlank()
                ? sessionProfile.userId() : "";
        setGravity(Gravity.TOP);
        LinearLayout topPanel = new LinearLayout(context);
        topPanel.setOrientation(LinearLayout.HORIZONTAL);
        topPanel.setGravity(Gravity.LEFT);

        UrlImageView avatar = new UrlImageView(context);
        avatar.setCircular(true);
        LayoutParams layoutParams = new LayoutParams(dp(80), dp(80));
        avatar.setLayoutParams(layoutParams);
        topPanel.addView(avatar);
        avatar.loadUrl(avatarUrl == null || avatarUrl.isBlank() ? MusicHud.ICON_BASE64 : avatarUrl);

        LayoutParams infoLp1 = new LayoutParams(0, WRAP_CONTENT, 1);
        infoLp1.setMargins(dp(8), 0, 0, 0);
        LinearLayout infoLayout = new LinearLayout(context);
        infoLayout.setOrientation(VERTICAL);
        infoLayout.setGravity(Gravity.CENTER_VERTICAL);
        topPanel.addView(infoLayout, infoLp1);

        LayoutParams nameLayoutParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        TextView nickName = new TextView(context);
        nickName.setSingleLine(true);
        nickName.setTextSize(Theme.TEXT_SIZE_LARGER);
        nickName.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        nickName.setText(displayName);
        infoLayout.addView(nickName, nameLayoutParams);

        LayoutParams idLayoutParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        TextView id = new TextView(context);
        id.setSingleLine(true);
        id.setTextSize(Theme.TEXT_SIZE_NORMAL);
        id.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        id.setText(displayId);
        infoLayout.addView(id, idLayoutParams);

        LinearLayout membership = new LinearLayout(context);
        membership.setGravity(Gravity.CENTER_VERTICAL);
        membership.setVisibility(GONE);
        infoLayout.addView(membership, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        TuneWeavePlatform membershipPlatform = selectedPlatform;
        Object membershipAccount = indi.mopelotus.musichud.client.services.music.MusicEntityCache.captureGeneration();
        try {
            CompletableFuture.supplyAsync(tuneWeave.prepareAccountRequest(membershipPlatform,
                    () -> tuneWeave.loadMembership(membershipPlatform)), MusicHud.EXECUTOR).thenAccept(value -> post(generation, () -> {
                if (membershipAccount != indi.mopelotus.musichud.client.services.music.MusicEntityCache.captureGeneration()) return;
                if (value.level() == null && value.active() == null && value.iconUrl().isBlank()) return;
                if (!value.iconUrl().isBlank()) {
                    UrlImageView icon = new UrlImageView(context);
                    icon.setAspectRatio(0);
                    icon.setSquareCrop(false);
                    icon.setCornerRadius(0);
                    membership.addView(icon, new LayoutParams(dp(48), dp(20)));
                    icon.loadUrl(value.iconUrl());
                }
                TextView badge = new TextView(context);
                badge.setTextSize(Theme.TEXT_SIZE_SMALL);
                badge.setTextColor(Boolean.TRUE.equals(value.active()) ? Theme.PRIMARY_COLOR : Theme.SECONDARY_TEXT_COLOR);
                String label = value.level() == null ? "" : I18n.get(MusicHud.MOD_ID + ".text.membershipLevel", value.level());
                if (value.active() != null) label += (label.isBlank() ? "" : " · ")
                        + I18n.get(MusicHud.MOD_ID + (value.active() ? ".text.membershipActive" : ".text.membershipInactive"));
                badge.setText(label);
                membership.addView(badge, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
                membership.setVisibility(VISIBLE);
            })).exceptionally(error -> null);
        } catch (RuntimeException unavailable) { /* Optional metadata must not block account collections. */ }


        InsetBackgroundFactory cardBackground = InsetBackgroundFactory.builder()
                .backgroundColor(Theme.GHOST_BUTTON_STATES).inset(dp(1)).cornerRadius(dp(4))
                .padding(new InsetBackgroundFactory.Padding(dp(8), dp(6), dp(8), dp(6))).build();
        FlexWrapLayout buttons = new FlexWrapLayout(context);
        buttons.setAnimationsEnabled(false);
        infoLayout.addView(buttons, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        buildCard(context, cardBackground, buttons, ".button.history", "rotate_ccw_clock.png", view -> {
            if (!callbacks.isCurrent(generation)) return;
            RouterContainer router = RouterContainer.getInstance();
            if (router != null) router.pushNavigate(new RecentHistoryView(context, selectedPlatform));
        });
        if (selectedPlatform == TuneWeavePlatform.NETEASE) {
            buildCard(context, cardBackground, buttons, ".button.cloud", "cloud.png", view ->
                    RouterContainer.getInstance().pushNavigate(new CloudView(context)));
            buildCard(context, cardBackground, buttons, ".button.programs", "radio.png", view ->
                    RouterContainer.getInstance().pushNavigate(new PodcastRadioView(context)));
        }
        if (selectedPlatform != TuneWeavePlatform.BILIBILI) {
            buildCard(context, cardBackground, buttons, ".button.managePlaylists", "list_music.png", view ->
                    RouterContainer.getInstance().pushNavigate(new PlatformPlaylistManagerView(context)));
        }
        InsetBackgroundFactory iconBackground = InsetBackgroundFactory.builder()
                .backgroundColor(Theme.GHOST_BUTTON_STATES).inset(dp(1)).cornerRadius(dp(4))
                .padding(new InsetBackgroundFactory.Padding(dp(6), dp(6), dp(6), dp(6))).build();
        buildIcon(context, iconBackground, buttons, ".button.refresh", "rotate_cw.png", view -> refresh(true));
        buildIcon(context, iconBackground, buttons, ".button.logout", "log_out.png", view -> clientLoginService.logout());

        LayoutParams topPanelLayoutParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        topPanelLayoutParams.setMargins(0, dp(32), 0, dp(32));
        addView(topPanel, topPanelLayoutParams);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        content.setLayoutParams(new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        addView(content);

        {
            myPlaylistsContent = new LinearLayout(context);
            myPlaylistsContent.setOrientation(VERTICAL);
            myPlaylistsContent.setVisibility(GONE);
            LayoutParams myPlaylistsContentParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            myPlaylistsContentParams.setMargins(0, 0, 0, dp(32));
            content.addView(myPlaylistsContent, myPlaylistsContentParams);

            TextView myPlaylistsText = new TextView(context);
            myPlaylistsText.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            myPlaylistsText.setTextSize(Theme.TEXT_SIZE_LARGE);
            myPlaylistsText.setText(I18n.get(MusicHud.MOD_ID + ".text.myPlaylists"));
            LayoutParams titleParam = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            titleParam.setMargins(0, 0, 0, dp(16));
            myPlaylistsContent.addView(myPlaylistsText, titleParam);

            myPlaylistCards = new FlexWrapLayout(context);
            myPlaylistsContent.addView(myPlaylistCards, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
        {
            mySubscribedPlaylistsContent = new LinearLayout(context);
            mySubscribedPlaylistsContent.setOrientation(VERTICAL);
            mySubscribedPlaylistsContent.setVisibility(GONE);
            LayoutParams mySubscribedPlaylistsContentParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            mySubscribedPlaylistsContentParams.setMargins(0, 0, 0, dp(32));
            content.addView(mySubscribedPlaylistsContent, mySubscribedPlaylistsContentParams);

            TextView subscribedPlaylistsText = new TextView(context);
            subscribedPlaylistsText.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            subscribedPlaylistsText.setTextSize(Theme.TEXT_SIZE_LARGE);
            subscribedPlaylistsText.setText(I18n.get(MusicHud.MOD_ID + ".text.mySubscribedPlaylists"));
            LayoutParams titleParam = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            titleParam.setMargins(0, 0, 0, dp(16));
            mySubscribedPlaylistsContent.addView(subscribedPlaylistsText, titleParam);

            mySubscribedPlaylistCards = new FlexWrapLayout(context);
            mySubscribedPlaylistsContent.addView(mySubscribedPlaylistCards, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
        {
            mySubscribedAlbumsContent = new LinearLayout(context);
            mySubscribedAlbumsContent.setOrientation(VERTICAL);
            mySubscribedAlbumsContent.setVisibility(GONE);
            LayoutParams mySubscribedAlbumsContentParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            mySubscribedAlbumsContentParams.setMargins(0, 0, 0, dp(32));
            content.addView(mySubscribedAlbumsContent, mySubscribedAlbumsContentParams);

            TextView subscribedAlbumsText = new TextView(context);
            subscribedAlbumsText.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            subscribedAlbumsText.setTextSize(Theme.TEXT_SIZE_LARGE);
            subscribedAlbumsText.setText(I18n.get(MusicHud.MOD_ID + ".text.myAlbums"));
            LayoutParams titleParam = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            titleParam.setMargins(0, 0, 0, dp(16));
            mySubscribedAlbumsContent.addView(subscribedAlbumsText, titleParam);

            albumCards = new FlexWrapLayout(context);
            mySubscribedAlbumsContent.addView(albumCards, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
        {
            mySubscribedArtistsContent = new LinearLayout(context);
            mySubscribedArtistsContent.setOrientation(VERTICAL);
            mySubscribedArtistsContent.setVisibility(GONE);
            LayoutParams mySubscribedArtistsContentParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            mySubscribedArtistsContentParams.setMargins(0, 0, 0, dp(32));
            content.addView(mySubscribedArtistsContent, mySubscribedArtistsContentParams);

            TextView artistText = new TextView(context);
            artistText.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            artistText.setTextSize(Theme.TEXT_SIZE_LARGE);
            artistText.setText(I18n.get(MusicHud.MOD_ID + ".text.myArtists"));
            LayoutParams titleParam = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            titleParam.setMargins(0, 0, 0, dp(16));
            mySubscribedArtistsContent.addView(artistText, titleParam);

            artistCards = new FlexWrapLayout(context);
            mySubscribedArtistsContent.addView(artistCards, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }

        MusicService musicService = MusicService.getInstance();
        this.<UserCategoryPlaylists>loadModule(musicService::loadAccountPlaylists, myPlaylistsContent,
                category -> renderPlaylists(category, generation), generation, ignoreCache);
        this.<ObservableSequencedSet<Album>>loadModule(musicService::loadAccountAlbums, mySubscribedAlbumsContent,
                albums -> renderAlbums(albums, generation), generation, ignoreCache);
        this.<ObservableSequencedSet<Artist>>loadModule(musicService::loadAccountArtists, mySubscribedArtistsContent,
                artists -> renderArtists(artists, generation), generation, ignoreCache);
    }

    private void post(long generation, Runnable callback) {
        callbacks.post(MuiModApi::postToUiThread, generation, () -> {
            if (isAttachedToWindow()) callback.run();
        });
    }

    private <T> void loadModule(java.util.function.BiFunction<Boolean, Consumer<T>, CompletableFuture<T>> request, LinearLayout section,
                                Consumer<T> render, long generation, boolean refresh) {
        section.setVisibility(VISIBLE);
        LinearLayout status = new LinearLayout(getContext());
        status.setOrientation(VERTICAL);
        section.addView(status, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        ProgressBar loading = new ProgressBar(getContext());
        loading.setIndeterminate(true);
        status.addView(loading, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        CompletableFuture<T> future;
        try {
            future = request.apply(refresh, partial -> post(generation, () -> {
                render.accept(partial);
                section.setVisibility(VISIBLE);
            }));
        } catch (RuntimeException error) {
            future = CompletableFuture.failedFuture(error);
        }
        future.whenComplete((value, error) -> post(generation, () -> {
            status.removeAllViews();
            if (error == null) {
                section.removeView(status);
                render.accept(value);
            } else {
                section.setVisibility(VISIBLE);
                TextView message = new TextView(getContext());
                message.setText(I18n.get(MusicHud.MOD_ID + ".text.accountLoadError"));
                message.setTextColor(Theme.SECONDARY_TEXT_COLOR);
                status.addView(message);
                Button retry = new Button(getContext());
                retry.setText(I18n.get(MusicHud.MOD_ID + ".button.retry"));
                retry.setOnClickListener(view -> {
                    if (!callbacks.isCurrent(generation)) return;
                    section.removeView(status);
                    loadModule(request, section, render, generation, false);
                });
                status.addView(retry);
            }
        }));
    }

    private void renderPlaylists(UserCategoryPlaylists category, long generation) {
        if (playlistAddRegister != null) playlistAddRegister.unregister();
        if (playlistRemoveRegister != null) playlistRemoveRegister.unregister();
        myPlaylistCards.removeAllViews();
        mySubscribedPlaylistCards.removeAllViews();
        elementMap.keySet().removeIf(key -> key.clazz() == Playlist.class);
        Playlist liked = category.getLikeList();
        boolean hasLiked = liked != null && liked != Playlist.EMPTY;
        if (hasLiked) addCreatedPlaylist(liked);
        var created = category.getCreatedPlaylist();
        if (created != null) created.forEach(this::addCreatedPlaylist);
        var subscribed = category.getSubscribedPlaylist();
        if (subscribed != null) {
            subscribed.forEach(playlist -> createPlaylistCard(playlist, generation));
            playlistAddRegister = subscribed.registerOnAdd(playlist -> createPlaylistCard(playlist, generation));
            playlistRemoveRegister = subscribed.registerOnRemove(playlist -> post(generation, () -> {
                View card = elementMap.remove(new ElementKey(Playlist.class, playlist.getId()));
                if (card != null) mySubscribedPlaylistCards.removeView(card);
                mySubscribedPlaylistsContent.setVisibility(subscribed.isEmpty() ? GONE : VISIBLE);
            }));
        }
        myPlaylistsContent.setVisibility(!hasLiked && (created == null || created.isEmpty()) ? GONE : VISIBLE);
        mySubscribedPlaylistsContent.setVisibility(subscribed == null || subscribed.isEmpty() ? GONE : VISIBLE);
    }

    private void addCreatedPlaylist(Playlist playlist) {
        elementMap.computeIfAbsent(new ElementKey(Playlist.class, playlist.getId()), key -> {
            MusicCollectionCard card = new MusicCollectionCard(getContext(), playlist);
            card.setTag(playlist.getId());
            myPlaylistCards.addView(card);
            return card;
        });
    }

    private void renderAlbums(ObservableSequencedSet<Album> albums, long generation) {
        if (albumAddRegister != null) albumAddRegister.unregister();
        if (albumRemoveRegister != null) albumRemoveRegister.unregister();
        albums.forEach(album -> createAlbumCard(album, generation));
        albumAddRegister = albums.registerOnAdd(album -> createAlbumCard(album, generation));
        albumRemoveRegister = albums.registerOnRemove(album -> post(generation, () -> {
            View card = elementMap.remove(new ElementKey(Album.class, album.getId()));
            if (card != null) albumCards.removeView(card);
            mySubscribedAlbumsContent.setVisibility(albums.isEmpty() ? GONE : VISIBLE);
        }));
        mySubscribedAlbumsContent.setVisibility(albums.isEmpty() ? GONE : VISIBLE);
    }

    private void renderArtists(ObservableSequencedSet<Artist> artists, long generation) {
        if (artistAddRegister != null) artistAddRegister.unregister();
        if (artistRemoveRegister != null) artistRemoveRegister.unregister();
        artists.forEach(artist -> createArtistCard(artist, generation));
        artistAddRegister = artists.registerOnAdd(artist -> createArtistCard(artist, generation));
        artistRemoveRegister = artists.registerOnRemove(artist -> post(generation, () -> {
            View card = elementMap.remove(new ElementKey(Artist.class, artist.getId()));
            if (card != null) artistCards.removeView(card);
            mySubscribedArtistsContent.setVisibility(artists.isEmpty() ? GONE : VISIBLE);
        }));
        mySubscribedArtistsContent.setVisibility(artists.isEmpty() ? GONE : VISIBLE);
    }
    private record ElementKey(Class<?> clazz, long id) {
    }
}
