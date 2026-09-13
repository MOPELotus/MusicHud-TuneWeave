package indi.mopelotus.musichud.client.ui.pages.search;

import icyllis.modernui.animation.MotionEasingUtils;
import icyllis.modernui.animation.ObjectAnimator;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.ui.ClampingScrollView;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.OneShotPreDrawListener;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.api.SearchType;
import indi.mopelotus.musichud.client.ui.Theme;
import lombok.Getter;
import lombok.NonNull;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public class SearchResultTabPage extends FrameLayout {
    @Getter
    private final ViewPager pager;
    private final SearchView owner;

    public SearchResultTabPage(Context context, SearchView owner) {
        super(context);
        this.owner = owner;

        pager = new ViewPager(context);
        {
            pager.setAdapter(new ResultAdapter());
            pager.setFocusableInTouchMode(true);
            pager.setKeyboardNavigationCluster(true);

            // 添加进入动画
            OneShotPreDrawListener.add(pager, () -> {
                var animator = ObjectAnimator.ofFloat(pager,
                        View.ROTATION_Y, pager.isLayoutRtl() ? -45 : 45, 0);
                animator.setInterpolator(MotionEasingUtils.MOTION_EASING_EMPHASIZED);
                animator.start();
            });
        }

        TabLayout tabLayout = new TabLayout(context);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(@NotNull TabLayout.Tab tab) {
                if (SearchView.getInstance() == owner) owner.refreshSearch(false);
            }
        });
        tabLayout.setElevation(dp(3));
        tabLayout.setTabMode(TabLayout.MODE_AUTO);
        tabLayout.setTabGravity(TabLayout.GRAVITY_CENTER);
        tabLayout.setupWithViewPager(pager);
        tabLayout.setBackground(null);

        var lp = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        tabLayout.setLayoutParams(lp);
        addView(tabLayout);

        // 添加 ViewPager 到剩余空间
        var pagerLp = new LayoutParams(MATCH_PARENT, MATCH_PARENT);
        pagerLp.gravity = Gravity.TOP;
        pagerLp.topMargin = dp(48);
        addView(pager, pagerLp);

        addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (pager.getAdapter() instanceof ResultAdapter adapter) {
                    adapter.unregisterAllListeners();
                }
                clearResult();
            }
        });
    }

    public void clearResult() {
        if (SearchView.getInstance() != owner) return;
        SearchAlbumResultView.setResult(null);
        SearchArtistResultView.setResult(null);
        SearchPlaylistResultView.setResult(null);
        SearchMusicResultView.setResult(null);
        SearchPodcastResultView.setResult(null);
        if (pager.getAdapter() != null) pager.getAdapter().notifyDataSetChanged();
    }

    private class ResultAdapter extends PagerAdapter {
        private final Map<View, Consumer<SearchView.SearchMeta>> registeredListeners = new HashMap<>();

        @Override
        public int getCount() {
            return 5; // 页面数量
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            var context = container.getContext();

            ClampingScrollView sv = new ClampingScrollView(context);
            sv.setTag(position);

            ProgressBar loadingMoreProgressBar = new ProgressBar(context);
            loadingMoreProgressBar.setVisibility(GONE);
            loadingMoreProgressBar.setIndeterminate(true);

            TextView noMoreResultText = new TextView(getContext());
            noMoreResultText.setText(I18n.get(MusicHud.MOD_ID + ".text.searchNoMoreResult"));
            noMoreResultText.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            noMoreResultText.setTextSize(Theme.TEXT_SIZE_NORMAL);
            noMoreResultText.setTextAlignment(TEXT_ALIGNMENT_CENTER);
            noMoreResultText.setVisibility(GONE);
            noMoreResultText.setOnClickListener(view -> {
                if (SearchView.getInstance() == owner) owner.loadMoreSearchResult();
            });

            sv.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (scrollY > oldScrollY) {
                    checkInfiniteScroll(scrollY, sv);
                }
            });
            container.addView(sv);

            SearchType searchType;
            ViewGroup layout = switch (position) {
                case 0 -> {
                    searchType = SearchType.MUSIC;
                    yield new SearchMusicResultView(context);
                }
                case 1 -> {
                    searchType = SearchType.PLAYLIST;
                    yield new SearchPlaylistResultView(context);
                }
                case 2 -> {
                    searchType = SearchType.ALBUM;
                    yield new SearchAlbumResultView(context);
                }
                case 3 -> {
                    searchType = SearchType.ARTIST;
                    yield new SearchArtistResultView(context);
                }
                case 4 -> {
                    searchType = SearchType.RADIO;
                    yield new SearchPodcastResultView(context);
                }
                default -> {
                    searchType = SearchType.MUSIC;
                    yield new SearchMusicResultView(context);
                }
            };
            Consumer<SearchView.SearchMeta> refreshListener = (searchMeta) -> {
                if (searchMeta.getSearchType() == searchType) {
                    CompletableFuture<SearchView.CompletingType> pendingFuture = searchMeta.pendingFuture;
                    checkFuture(noMoreResultText, loadingMoreProgressBar, pendingFuture, searchType);
                    if (pendingFuture == null) noMoreResultText.setVisibility(searchMeta.isMayHasMore() ? GONE : VISIBLE);
                }
            };
            SearchView instance = owner;
            if (instance != null) {
                instance.getSearchRefreshListeners().add(refreshListener);
                registeredListeners.put(sv, refreshListener);
                var previous = owner.getSearchMetas().get(searchType);
                if (previous != null) refreshListener.accept(previous);
            }

            LinearLayout ll = new LinearLayout(context);
            ll.setOrientation(LinearLayout.VERTICAL);

            var vgParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            vgParams.gravity = Gravity.CENTER_HORIZONTAL;
            ll.addView(layout, vgParams);

            var progressParams = new LinearLayout.LayoutParams(MATCH_PARENT, dp(48), 0);
            progressParams.setMargins(0, dp(16), 0, dp(16));
            progressParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            ll.addView(loadingMoreProgressBar, progressParams);

            var tParams = new LinearLayout.LayoutParams(MATCH_PARENT, dp(16), 0);
            tParams.setMargins(0, dp(32), 0, dp(32));
            tParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            ll.addView(noMoreResultText, tParams);

            var llParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            llParams.gravity = Gravity.CENTER_HORIZONTAL;
            sv.addView(ll, llParams);

            return sv;
        }

        private void checkInfiniteScroll(int scrollY, ClampingScrollView sv) {
            if (sv.getChildCount() > 0) {
                View child = sv.getChildAt(0);
                int contentHeight = child.getHeight();
                int viewHeight = sv.getHeight();
                int maxScroll = Math.max(0, contentHeight - viewHeight);
                int threshold = sv.dp(100);

                if (maxScroll - scrollY <= threshold) {
                    SearchView instance = owner;
                    if (instance != null) {
                        instance.loadMoreSearchResult();
                    }
                }
            }
        }

        private void checkFuture(TextView noMore, ProgressBar loading, CompletableFuture<SearchView.CompletingType> future, SearchType searchType) {
            loading.setTag(future);
            if (future == null) { loading.setVisibility(GONE); return; }
            loading.setVisibility(future.isDone() ? GONE : VISIBLE);
            noMore.setVisibility(GONE);
            future.whenComplete((result, error) -> MuiModApi.postToUiThread(() -> {
                if (SearchView.getInstance() != owner || !loading.isAttachedToWindow() || loading.getTag() != future) return;
                loading.setVisibility(GONE);
                boolean failed = error != null || result == SearchView.CompletingType.FAILED;
                String failureText = failed ? owner.searchFailureMessage(searchType) : null;
                noMore.setText(I18n.get(failureText == null ? MusicHud.MOD_ID + ".text.searchNoMoreResult" : failureText));
                noMore.setVisibility(failed || result == SearchView.CompletingType.NO_MORE_RESULT ? VISIBLE : GONE);
            }));
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            if (object instanceof View view) {
                container.removeView(view);
                SearchType searchType = switch (position) {
                    case 0 -> SearchType.MUSIC;
                    case 1 -> SearchType.PLAYLIST;
                    case 2 -> SearchType.ALBUM;
                    case 3 -> SearchType.ARTIST;
                    case 4 -> SearchType.RADIO;
                    default -> SearchType.MUSIC;
                };
                SearchView instance = owner;
                if (instance != null) {
                    Consumer<SearchView.SearchMeta> listener = registeredListeners.remove(object);
                    if (listener != null) {
                        instance.getSearchRefreshListeners().remove(listener);
                    }
                }
            }
        }

        void unregisterAllListeners() {
            SearchView instance = owner;
            if (instance != null) {
                instance.getSearchRefreshListeners().removeAll(registeredListeners.values());
            }
            registeredListeners.clear();
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return I18n.get(switch (position) {
                case 0 -> MusicHud.MOD_ID + ".text.page.search.music";
                case 1 -> MusicHud.MOD_ID + ".text.page.search.playlist";
                case 2 -> MusicHud.MOD_ID + ".text.page.search.album";
                case 3 -> MusicHud.MOD_ID + ".text.page.search.artist";
                case 4 -> MusicHud.MOD_ID + ".text.page.search.radio";
                default -> "";
            });
        }
    }
}
