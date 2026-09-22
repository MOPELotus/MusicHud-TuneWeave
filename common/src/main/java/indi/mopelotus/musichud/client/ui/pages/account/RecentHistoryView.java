package indi.mopelotus.musichud.client.ui.pages.account;

import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.drawable.InsetDrawable;
import icyllis.modernui.mc.ui.ClampingScrollView;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveRecentHistory;
import indi.mopelotus.musichud.client.ui.ClientViewTasks;
import indi.mopelotus.musichud.client.ui.ScopedViewTasks;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.MusicListFactory;
import indi.mopelotus.musichud.client.ui.components.MusicListItem;
import indi.mopelotus.musichud.client.ui.components.RouterContainer;
import indi.mopelotus.musichud.client.ui.components.MusicCollectionCard;
import indi.mopelotus.musichud.client.ui.components.WaterfallLayout;
import indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.NonNull;
import net.minecraft.client.resources.language.I18n;

import java.util.HashMap;
import java.util.Map;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * Full-screen account play-history page with three tabs (songs / albums / playlists).
 * Opened from {@link AccountView} through {@link RouterContainer#pushNavigate(View)}.
 */
public class RecentHistoryView extends LinearLayout {
    private final TuneWeavePlatform platform;
    private ViewPager pager;

    public RecentHistoryView(Context context, TuneWeavePlatform platform) {
        super(context);
        this.platform = platform;
        setLayoutParams(new LayoutParams(MATCH_PARENT, MATCH_PARENT));
        setOrientation(VERTICAL);

        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
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
        title.setText(I18n.get(MusicHud.MOD_ID + ".button.history"));
        LayoutParams titleParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        titleParams.setMargins(dp(12), 0, dp(4), 0);
        topBar.addView(title, titleParams);

        InsetBackgroundFactory refreshBackgroundFactory = InsetBackgroundFactory.builder()
                .backgroundColor(Theme.GHOST_BUTTON_STATES)
                .inset(dp(1))
                .cornerRadius(dp(4))
                .padding(new InsetBackgroundFactory.Padding(dp(4), dp(4), dp(4), dp(4)))
                .build();
        ImageButton refreshButton = new ImageButton(context);
        refreshBackgroundFactory.applyBackgroundTo(refreshButton);
        refreshButton.setTooltipText(I18n.get(MusicHud.MOD_ID + ".button.refresh"));
        refreshButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        Image refreshIcon = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/rotate_cw.png");
        if (refreshIcon != null) {
            refreshButton.setImageDrawable(new InsetDrawable(
                    new ScaledImageDrawable(getContext().getResources(), refreshIcon, dp(12), dp(16)), dp(3)));
        }
        refreshButton.setOnClickListener(view -> {
            if (pager != null && pager.getAdapter() instanceof HistoryPagerAdapter adapter) {
                HistoryPage page = adapter.getPage(pager.getCurrentItem());
                if (page != null) {
                    page.reload();
                }
            }
        });
        topBar.addView(refreshButton, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        TabLayout tabLayout = new TabLayout(context);
        tabLayout.setElevation(dp(3));
        tabLayout.setTabMode(TabLayout.MODE_AUTO);
        tabLayout.setTabGravity(TabLayout.GRAVITY_CENTER);
        tabLayout.setBackground(null);
        LayoutParams params = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params.setMargins(dp(16), 0, 0, 0);
        topBar.addView(tabLayout, params);

        pager = new ViewPager(context);
        pager.setAdapter(new HistoryPagerAdapter());
        pager.setFocusableInTouchMode(true);
        pager.setKeyboardNavigationCluster(true);
        tabLayout.setupWithViewPager(pager);
        addView(pager, new LayoutParams(MATCH_PARENT, 0, 1));
    }

    private class HistoryPagerAdapter extends PagerAdapter {
        private final Map<Integer, HistoryPage> pages = new HashMap<>();

        @Override
        public int getCount() {
            return 3;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            HistoryPage page = new HistoryPage(container.getContext(), platform, position);
            pages.put(position, page);
            container.addView(page, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            return page;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            pages.remove(position);
            if (object instanceof View view) {
                container.removeView(view);
            }
        }

        HistoryPage getPage(int position) {
            return pages.get(position);
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return I18n.get(switch (position) {
                case 0 -> MusicHud.MOD_ID + ".text.history.tracks";
                case 1 -> MusicHud.MOD_ID + ".text.history.albums";
                case 2 -> MusicHud.MOD_ID + ".text.history.playlists";
                default -> "";
            });
        }
    }

    /**
     * One tab page. Loads its record list lazily on first attach and renders it into a
     * scrolling column.
     */
    private static class HistoryPage extends FrameLayout {
        private final ScopedViewTasks tasks = ClientViewTasks.create();
        private final TuneWeavePlatform platform;
        private final TuneWeaveRecentHistory.Kind kind;
        private final LinearLayout content;
        private final ProgressBar progressBar;
        private final LinearLayout message;
        private final TextView status;
        private final Button retry;
        private TuneWeaveRecentHistory cached;
        private Object cachedAccount;
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault());

        HistoryPage(Context context, TuneWeavePlatform platform, int position) {
            super(context);
            this.platform = platform;
            kind = TuneWeaveRecentHistory.Kind.values()[position];
            ClampingScrollView scrollView = new ClampingScrollView(context);
            scrollView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            addView(scrollView, new LayoutParams(MATCH_PARENT, MATCH_PARENT));
            content = new LinearLayout(context);
            content.setOrientation(VERTICAL);
            scrollView.addView(content, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

            progressBar = new ProgressBar(context);
            progressBar.setIndeterminate(true);
            LayoutParams progressParams = new LayoutParams(dp(48), dp(48));
            progressParams.gravity = Gravity.CENTER;
            addView(progressBar, progressParams);

            message = new LinearLayout(context);
            message.setOrientation(VERTICAL);
            message.setGravity(Gravity.CENTER);
            message.setVisibility(GONE);
            LayoutParams messageParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            messageParams.gravity = Gravity.CENTER;
            addView(message, messageParams);
            status = label("");
            message.addView(status, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            retry = new Button(context);
            retry.setText(text(".button.retry"));
            retry.setTextColor(Theme.PRIMARY_COLOR);
            retry.setTextSize(Theme.TEXT_SIZE_NORMAL);
            InsetBackgroundFactory.builder().inset(dp(2)).cornerRadius(dp(4))
                    .padding(new InsetBackgroundFactory.Padding(dp(16), dp(8), dp(16), dp(8)))
                    .build().applyBackgroundTo(retry);
            retry.setOnClickListener(v -> reload());
            message.addView(retry, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

            addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    tasks.attach();
                    if (cached != null && cachedAccount == MusicEntityCache.captureGeneration()) render(cached);
                    else reload();
                }
                @Override public void onViewDetachedFromWindow(View v) { tasks.detach(); }
            });
        }

        void reload() {
            if (!tasks.isCurrent()) return;
            content.removeAllViews();
            cached = null;
            message.setVisibility(GONE);
            progressBar.setVisibility(VISIBLE);
            tasks.load(ignored -> TuneWeaveClientService.getInstance().loadRecentHistory(platform, kind), this::render, error -> {
                progressBar.setVisibility(GONE);
                message.setVisibility(VISIBLE);
                retry.setVisibility(VISIBLE);
                String key = error instanceof TuneWeaveException problem ? switch (problem.getCode()) {
                    case "authentication_required" -> "loginRequired";
                    case "capability_not_supported" -> "unsupported";
                    case "permission_denied" -> "permissionDenied";
                    case "rate_limited" -> "rateLimited";
                    default -> "failed";
                } : "failed";
                status.setText(text(".text.history." + key));
            });
        }

        private void render(TuneWeaveRecentHistory history) {
            cached = history;
            cachedAccount = MusicEntityCache.captureGeneration();
            progressBar.setVisibility(GONE);
            retry.setVisibility(GONE);
            message.setVisibility(history.entries().isEmpty() ? VISIBLE : GONE);
            status.setText(text(".text.history.empty"));
            content.removeAllViews();
            var token = tasks.capture();
            WaterfallLayout waterfall = new WaterfallLayout(getContext());
            waterfall.setRowMinWidth(dp(174));
            if (kind != TuneWeaveRecentHistory.Kind.TRACKS) {
                content.addView(waterfall, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            }
            // Preserve repeated plays of the same resource as separate history entries.
            for (var entry : history.entries()) {
                if (entry.resource() instanceof MusicDetail track) {
                    MusicListItem row = MusicListFactory.createItem(this, ignored -> !tasks.isCurrent(token));
                    row.setShowPusherInfo(false);
                    row.bindData(track);
                    row.setPlayRecordTime(entry.playedAt());
                    TextView device = label(entry.device() == null ? "" : entry.device().displayName());
                    if (entry.playedAt() == null) device.setText(text(".text.history.unknownTime") + " " + device.getText());
                    device.setSingleLine(true);
                    device.setPadding(dp(8), 0, 0, 0);
                    row.getInfoRow().addView(device);
                    row.setTooltipText(metadata(entry));
                    content.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                } else if (entry.resource() instanceof MusicCollection collection) {
                    LinearLayout item = new LinearLayout(getContext());
                    item.setOrientation(VERTICAL);
                    item.addView(new MusicCollectionCard(getContext(), collection));
                    TextView details = label(metadata(entry));
                    details.setMaxWidth(dp(164));
                    details.setMaxLines(2);
                    details.setPadding(dp(6), dp(4), dp(6), dp(12));
                    details.setTooltipText(metadata(entry));
                    item.addView(details, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                    waterfall.addView(item);
                }
            }
        }

        private String metadata(TuneWeaveRecentHistory.Entry<?> entry) {
            String time = entry.playedAt() == null ? text(".text.history.unknownTime") : TIME.format(entry.playedAt());
            String device = entry.device() == null ? "" : entry.device().displayName();
            return time + (device.isBlank() ? "" : " · " + device);
        }
        private TextView label(String value) {
            TextView view = new TextView(getContext());
            view.setText(value);
            view.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            view.setTextSize(Theme.TEXT_SIZE_NORMAL);
            return view;
        }
        private static String text(String key) { return I18n.get(MusicHud.MOD_ID + key); }
    }
}
