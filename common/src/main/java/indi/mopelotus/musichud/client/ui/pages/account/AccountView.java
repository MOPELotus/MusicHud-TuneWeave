package indi.mopelotus.musichud.client.ui.pages.account;

import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.Button;
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
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
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
            addView(new UniPlaylistView(context), new LayoutParams(MATCH_PARENT, 0, 1));
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
        LayoutParams layoutParams = new LayoutParams(dp(68), dp(68));
        avatar.setLayoutParams(layoutParams);
        topPanel.addView(avatar);
        avatar.loadUrl(avatarUrl == null || avatarUrl.isBlank() ? MusicHud.ICON_BASE64 : avatarUrl);

        LayoutParams infoLp1 = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        infoLp1.setMargins(dp(16), 0, 0, 0);
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

        ButtonInsetBackgroundFactory backgroundFactory = ButtonInsetBackgroundFactory.builder()
                .inset(0).cornerRadius(dp(4))
                .padding(new ButtonInsetBackgroundFactory.Padding(0, dp(2), 0, dp(2)))
                .build();

        LinearLayout buttonsLayout = new LinearLayout(context);
        buttonsLayout.setOrientation(LinearLayout.HORIZONTAL);
        infoLayout.addView(buttonsLayout);

        Button refreshButton = new Button(context);
        refreshButton.setTextColor(Theme.PRIMARY_COLOR);
        refreshButton.setTextSize(Theme.TEXT_SIZE_NORMAL);
        refreshButton.setText(I18n.get(MusicHud.MOD_ID + ".button.refresh"));
        var background1 = backgroundFactory.newBackgroundDrawable();
        refreshButton.setBackground(background1);
        LayoutParams params = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params.setMargins(0, 0, dp(8), 0);
        refreshButton.setLayoutParams(params);
        refreshButton.setOnClickListener(b -> {
            refresh(true);
        });
        buttonsLayout.addView(refreshButton);

        if (selectedPlatform != TuneWeavePlatform.BILIBILI) {
            Button managePlaylistsButton = new Button(context);
            managePlaylistsButton.setText(I18n.get(MusicHud.MOD_ID + ".button.managePlaylists"));
            managePlaylistsButton.setTextColor(Theme.PRIMARY_COLOR);
            managePlaylistsButton.setTextSize(Theme.TEXT_SIZE_NORMAL);
            managePlaylistsButton.setBackground(backgroundFactory.newBackgroundDrawable());
            managePlaylistsButton.setOnClickListener(button -> RouterContainer.getInstance().pushNavigate(
                    new PlatformPlaylistManagerView(context)));
            LayoutParams manageParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            manageParams.setMargins(0, 0, dp(8), 0);
            buttonsLayout.addView(managePlaylistsButton, manageParams);
        }

        if (TuneWeaveClientService.getInstance().defaultPlatform() == TuneWeavePlatform.NETEASE) {
            Button cloudButton = new Button(context);
            cloudButton.setText(I18n.get(MusicHud.MOD_ID + ".button.cloud"));
            cloudButton.setTextColor(Theme.PRIMARY_COLOR);
            cloudButton.setTextSize(Theme.TEXT_SIZE_NORMAL);
            cloudButton.setBackground(backgroundFactory.newBackgroundDrawable());
            cloudButton.setOnClickListener(button -> RouterContainer.getInstance().pushNavigate(new CloudView(context)));
            LayoutParams cloudParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            cloudParams.setMargins(0, 0, dp(8), 0);
            buttonsLayout.addView(cloudButton, cloudParams);

            Button programsButton = new Button(context);
            programsButton.setText(I18n.get(MusicHud.MOD_ID + ".button.programs"));
            programsButton.setTextColor(Theme.PRIMARY_COLOR);
            programsButton.setTextSize(Theme.TEXT_SIZE_NORMAL);
            programsButton.setBackground(backgroundFactory.newBackgroundDrawable());
            programsButton.setOnClickListener(button -> RouterContainer.getInstance().pushNavigate(
                    new PodcastRadioView(context)));
            LayoutParams programsParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            programsParams.setMargins(0, 0, dp(8), 0);
            buttonsLayout.addView(programsButton, programsParams);
        }

        Button logoutButton = new Button(context);
        logoutButton.setText(I18n.get(MusicHud.MOD_ID + ".button.logout"));
        logoutButton.setTextColor(Theme.PRIMARY_COLOR);
        logoutButton.setTextSize(Theme.TEXT_SIZE_NORMAL);
        var background2 = backgroundFactory.newBackgroundDrawable();
        logoutButton.setBackground(background2);
        logoutButton.setOnClickListener(b -> {
            clientLoginService.logout();
        });
        buttonsLayout.addView(logoutButton, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

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
