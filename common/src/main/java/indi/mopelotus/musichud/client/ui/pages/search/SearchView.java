package indi.mopelotus.musichud.client.ui.pages.search;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.Drawable;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.api.SearchType;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveSearchPage;
import indi.mopelotus.musichud.client.ui.components.PlatformSelector;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeavePodcast;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import net.minecraft.client.resources.language.I18n;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public class SearchView extends LinearLayout {
    @Getter
    private static SearchView instance = null;
    @Getter
    private final Map<SearchType, SearchMeta> searchMetas = new HashMap<>();
    @Getter
    private final HashSet<Consumer<SearchMeta>> searchRefreshListeners = new HashSet<>();
    private static final ClientConfig clientConfig = ClientConfig.getInstance();
    private EditText searchTextInput;
    private PlatformSelector platformSelector;
    private SearchResultTabPage searchResultTabPage;
    @Getter
    private String searchText;
    private final SearchRequestGate<SearchType> requests = new SearchRequestGate<>(MusicEntityCache::captureGeneration);

    public SearchView(Context context) {
        super(context);
        instance = this;
        refresh();
    }

    public void refresh() {
        cancelSearches();
        Context context = getContext();
        removeAllViews();
        setOrientation(VERTICAL);

        boolean enabled = clientConfig.getEnable();
        if (MusicHud.getConnectStatus() != MusicHud.ConnectStatus.CONNECTED && !ClientConfig.getInstance().getEnableIsolatedMode() || !enabled) {
            setGravity(Gravity.CENTER);
            TextView textView = Theme.getNotificationTextView(context, enabled);
            addView(textView);
            return;
        }

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(HORIZONTAL);
        LayoutParams topParams = new LayoutParams(MATCH_PARENT, dp(38));
        topParams.setMargins(0, dp(32), 0, 0);
        addView(top, topParams);

        top.addView(new View(context), new LayoutParams(0, WRAP_CONTENT, 2));
        platformSelector = new PlatformSelector(context, TuneWeavePlatform.values());
        platformSelector.setSelectedPlatform(TuneWeaveClientService.getInstance().defaultPlatform());
        top.addView(platformSelector, new LayoutParams(WRAP_CONTENT, MATCH_PARENT));
        searchTextInput = new EditText(context, null, R.attr.editTextOutlinedStyle);
        searchTextInput.setTextAlignment(SearchView.TEXT_ALIGNMENT_CENTER);
        searchTextInput.setHint(I18n.get(MusicHud.MOD_ID + ".field.hint.searchMusic"));
        searchTextInput.setSingleLine();
        LayoutParams params = new LayoutParams(0, WRAP_CONTENT, 6);
        params.setMargins(dp(52), 0, 0, 0);
        top.addView(searchTextInput, params);

        Button searchButton = new Button(context);
        searchButton.setText(I18n.get(MusicHud.MOD_ID + ".button.searchMusic"));
        LayoutParams buttonParams = new LayoutParams(WRAP_CONTENT, MATCH_PARENT);
        Drawable background = ButtonInsetBackgroundFactory.builder()
                .inset(0).padding(new ButtonInsetBackgroundFactory.Padding(dp(8), 0, dp(8), 0))
                .cornerRadius(dp(4)).build().newBackgroundDrawable();
        searchButton.setBackground(background);
        buttonParams.setMargins(dp(8), 0, 0, 0);
        top.addView(searchButton, buttonParams);

        top.addView(new View(context), new LayoutParams(0, WRAP_CONTENT, 2));

        searchResultTabPage = new SearchResultTabPage(context, this);
        searchResultTabPage.clearResult();

        LayoutParams resultAreaParams = new LayoutParams(MATCH_PARENT, 0, 1);
        resultAreaParams.setMargins(dp(32), 0, dp(32), 0);
        addView(searchResultTabPage, resultAreaParams);

        searchTextInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEY_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                refreshSearch(true);
                return true;
            }
            return false;
        });
        searchButton.setOnClickListener((v) -> refreshSearch(true));
        platformSelector.setOnPlatformSelectedListener(platform -> {
            cancelSearches(); searchResultTabPage.clearResult(); refreshSearch(true);
        });

        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) { instance = SearchView.this; }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (instance == SearchView.this) instance = null;
                cancelSearches();
            }
        });
    }

    public void refreshSearch(boolean force) {
        if (searchResultTabPage == null || searchTextInput == null) return;
        searchText = searchTextInput.getText().toString().trim();
        if (searchText == null || searchText.isEmpty()) return;
        int currentItem = searchResultTabPage.getPager().getCurrentItem();
        SearchType[] searchTypes = {SearchType.MUSIC, SearchType.PLAYLIST, SearchType.ALBUM, SearchType.ARTIST, SearchType.RADIO};
        SearchType searchType = searchTypes[currentItem];
        SearchMeta searchMeta = searchMetas.get(searchType);
        if (force || searchMeta == null || !searchMeta.text.equals(searchText)
                || searchMeta.platform != platformSelector.getSelectedPlatform() || searchMeta.account != MusicEntityCache.captureGeneration()) {
            if (searchMeta != null && searchMeta.pendingFuture != null) {
                searchMeta.pendingFuture.cancel(true);
            }
            SearchMeta searchMeta1 = new SearchMeta(searchType, searchText, platformSelector.getSelectedPlatform(), MusicEntityCache.captureGeneration());
            searchMeta1.pendingFuture = new CompletableFuture<>();
            searchMetas.put(searchType, searchMeta1);
            List.copyOf(searchRefreshListeners).forEach(listener -> listener.accept(searchMeta1));
            sendSearchRequest(searchText, searchType, 0);
        }
    }

    public void loadMoreSearchResult() {
        int currentItem = searchResultTabPage.getPager().getCurrentItem();
        SearchType[] searchTypes = {SearchType.MUSIC, SearchType.PLAYLIST, SearchType.ALBUM, SearchType.ARTIST, SearchType.RADIO};
        SearchType searchType = searchTypes[currentItem];
        SearchMeta searchMeta = searchMetas.get(searchType);
        if (searchMeta != null && (searchMeta.platform != platformSelector.getSelectedPlatform()
                || searchMeta.account != MusicEntityCache.captureGeneration())) { refreshSearch(true); return; }
        if (searchMeta != null && searchMeta.pendingFuture == null && searchMeta.mayHasMore) {
            int offset = searchMeta.nextOffset;
            searchMeta.pendingFuture = new CompletableFuture<>();
            List.copyOf(searchRefreshListeners).forEach(listener -> listener.accept(searchMeta));
            sendSearchRequest(searchMeta.text, searchType, offset);
        }
    }

    private void cancelSearches() {
        requests.clear();
        searchMetas.values().forEach(meta -> { if (meta.pendingFuture != null) meta.pendingFuture.cancel(false); });
        searchMetas.clear();
    }

    private void sendSearchRequest(String text, SearchType type, int offset) {
        SearchMeta meta = searchMetas.get(type);
        if (meta == null) return;
        var ticket = requests.begin(type);
        var prepared = TuneWeaveClientService.getInstance().prepareViewRequest(() ->
                TuneWeaveClientService.getInstance().searchPage(text, type, offset, meta.platform));
        MusicHud.EXECUTOR.execute(() -> {
            try {
                if (!requests.isCurrent(ticket)) return;
                var result = prepared.get();
                MuiModApi.postToUiThread(() -> {
                    if (instance != this || !requests.isCurrent(ticket) || searchMetas.get(type) != meta) return;
                    try { handleDirectSearchResult(offset, type, result); }
                    catch (RuntimeException error) { handleSearchFailure(type, error); }
                });
            } catch (RuntimeException error) {
                MuiModApi.postToUiThread(() -> {
                    if (instance == this && requests.isCurrent(ticket) && searchMetas.get(type) == meta) handleSearchFailure(type, error);
                });
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void handleDirectSearchResult(int offset, SearchType type, TuneWeaveSearchPage page) {
        List<?> result = page.items();
        switch (type) {
            case MUSIC -> setSearchMusicResult(offset, (List<MusicDetail>) result);
            case PLAYLIST -> setSearchPlaylistResult(offset, (List<Playlist>) result);
            case ALBUM -> setSearchAlbumResult(offset, (List<Album>) result);
            case ARTIST -> setSearchArtistResult(offset, (List<Artist>) result);
            case RADIO -> setSearchRadioResult(offset, (List<TuneWeavePodcast>) result);
            default -> { }
        }
        SearchMeta meta = searchMetas.get(type);
        meta.nextOffset = page.nextOffset();
        meta.mayHasMore = page.hasMore() && page.nextOffset() < 100_000;
        var pending = meta.pendingFuture; meta.pendingFuture = null;
        if (pending != null) pending.complete(meta.mayHasMore ? CompletingType.NORMAL : CompletingType.NO_MORE_RESULT);
    }

    private void handleSearchFailure(SearchType searchType, Throwable failure) {
        SearchMeta searchMeta = searchMetas.get(searchType);
        if (searchMeta == null) return;
        searchMeta.failureMessage = searchFailureMessage(failure);
        CompletableFuture<CompletingType> pendingFuture = searchMeta.pendingFuture;
        if (pendingFuture != null) {
            pendingFuture.complete(CompletingType.FAILED);
        }
        List.copyOf(searchRefreshListeners).forEach(listener -> listener.accept(searchMeta));
        searchMeta.pendingFuture = null;
    }

    public String searchFailureMessage(SearchType searchType) {
        SearchMeta meta = searchMetas.get(searchType);
        return meta == null || meta.failureMessage == null ? null : meta.failureMessage;
    }

    private static String searchFailureMessage(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException error) {
                if (error.getStatusCode() == 429 || "rate_limited".equals(error.getCode())) return "musichud_tuneweave.text.rateLimited";
                if (error.getStatusCode() == 401 || "authentication_required".equals(error.getCode())) return "musichud_tuneweave.text.loginRequired";
                break;
            }
            current = current.getCause();
        }
        return "musichud_tuneweave.button.loadingError";
    }

    public void setSearchMusicResult(int offset, List<MusicDetail> result) {
        SearchType searchType = SearchType.MUSIC;
        if (offset == 0) {
            SearchMusicResultView.setResult(result);
        } else {
            SearchMusicResultView instance = SearchMusicResultView.getInstance();
            if (instance != null) {
                instance.append(result);
            }
        }
    }

    public void setSearchPlaylistResult(int offset, List<Playlist> result) {
        SearchType searchType = SearchType.PLAYLIST;
        if (offset == 0) {
            SearchPlaylistResultView.setResult(result);
        } else {
            SearchPlaylistResultView instance = SearchPlaylistResultView.getInstance();
            if (instance != null) {
                instance.append(result);
            }
        }
    }

    public void setSearchAlbumResult(int offset, List<Album> result) {
        SearchType searchType = SearchType.ALBUM;
        if (offset == 0) {
            SearchAlbumResultView.setResult(result);
        } else {
            SearchAlbumResultView instance = SearchAlbumResultView.getInstance();
            if (instance != null) {
                instance.append(result);
            }
        }
    }

    public void setSearchArtistResult(int offset, List<Artist> result) {
        SearchType searchType = SearchType.ARTIST;
        if (offset == 0) {
            SearchArtistResultView.setResult(result);
        } else {
            SearchArtistResultView instance = SearchArtistResultView.getInstance();
            if (instance != null) {
                instance.append(result);
            }
        }
    }

    public void setSearchRadioResult(int offset, List<TuneWeavePodcast> result) {
        SearchType searchType = SearchType.RADIO;
        if (offset == 0) {
            SearchPodcastResultView.setResult(result);
        } else {
            SearchPodcastResultView instance = SearchPodcastResultView.getInstance();
            if (instance != null) instance.append(result);
        }
    }

    public enum CompletingType {
        NORMAL, NO_MORE_RESULT, FAILED
    }

    @Data
    @EqualsAndHashCode
    @ToString
    @Getter
    public static final class SearchMeta {
        private final SearchType searchType;
        private final String text;
        private final TuneWeavePlatform platform;
        private final Object account;
        CompletableFuture<CompletingType> pendingFuture;
        private int nextOffset = 0;
        private boolean mayHasMore = true;
        private String failureMessage;

        private SearchMeta(SearchType searchType, String text, TuneWeavePlatform platform, Object account) {
            this.searchType = searchType;
            this.text = text;
            this.platform = platform;
            this.account = account;
        }
    }
}
