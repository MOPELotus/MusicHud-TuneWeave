package indi.mopelotus.musichud.client.ui.pages.account;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ProgressBar;
import icyllis.modernui.widget.TabLayout;
import icyllis.modernui.widget.TextView;
import icyllis.modernui.widget.Toast;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveRecentHistory;
import indi.mopelotus.musichud.client.ui.ClientViewTasks;
import indi.mopelotus.musichud.client.ui.ScopedViewTasks;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import indi.mopelotus.musichud.client.ui.components.FlexWrapLayout;
import indi.mopelotus.musichud.client.ui.components.MusicCollectionCard;
import indi.mopelotus.musichud.client.ui.components.MusicListItem;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import net.minecraft.client.resources.language.I18n;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** AccountView supplies scrolling, account switching and lifetime; reuse the existing media widgets. */
public final class RecentHistoryView extends LinearLayout {
    private final ScopedViewTasks tasks = ClientViewTasks.create();
    private final TuneWeavePlatform platform;
    private final TextView status;
    private final ProgressBar loading;
    private final Button retry;
    private final LinearLayout rows;
    private TuneWeaveRecentHistory.Kind selected = TuneWeaveRecentHistory.Kind.TRACKS;
    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public RecentHistoryView(Context context, TuneWeavePlatform platform, Runnable back) {
        super(context);
        this.platform = platform;
        setOrientation(VERTICAL);
        setPadding(dp(16), 0, dp(16), dp(24));

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(button(".button.back", back), new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        TextView title = label(text(".text.history.title"));
        title.setTextSize(Theme.TEXT_SIZE_LARGE);
        title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        toolbar.addView(title, new LayoutParams(0, WRAP_CONTENT, 1));
        toolbar.addView(button(".button.refresh", this::refresh), new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        addView(toolbar, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        TabLayout tabs = new TabLayout(context);
        tabs.setTabMode(TabLayout.MODE_AUTO);
        tabs.setTabGravity(TabLayout.GRAVITY_CENTER);
        tabs.setBackground(null);
        for (var kind : TuneWeaveRecentHistory.Kind.values()) {
            tabs.addTab(tabs.newTab().setText(text(".text.history." + kind.path())));
        }
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                selected = TuneWeaveRecentHistory.Kind.values()[tab.getPosition()];
                refresh();
            }
        });
        addView(tabs, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        status = label("");
        status.setPadding(dp(4), dp(12), dp(4), dp(12));
        addView(status, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        loading = new ProgressBar(context);
        loading.setIndeterminate(true);
        addView(loading, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        retry = button(".button.retry", this::refresh);
        addView(retry, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        addView(rows, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { tasks.attach(); refresh(); }
            @Override public void onViewDetachedFromWindow(View view) {
                tasks.detach();
                rows.removeAllViews();
            }
        });
    }

    private void refresh() {
        if (!tasks.isCurrent()) return;
        var kind = selected;
        rows.removeAllViews();
        loading.setVisibility(VISIBLE);
        retry.setVisibility(GONE);
        status.setText(text(".text.history.loading"));
        tasks.load(ignored -> TuneWeaveClientService.getInstance().loadRecentHistory(platform, kind),
                this::render, error -> {
                    loading.setVisibility(GONE);
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

    private void render(TuneWeaveRecentHistory page) {
        loading.setVisibility(GONE);
        retry.setVisibility(GONE);
        rows.removeAllViews();
        status.setText(page.entries().isEmpty() ? text(".text.history.empty")
                : text(".text.history.count").replace("{}", Integer.toString(page.entries().size())));
        var token = tasks.capture();
        FlexWrapLayout collections = new FlexWrapLayout(getContext());
        if (page.kind() != TuneWeaveRecentHistory.Kind.TRACKS) {
            rows.addView(collections, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
        for (var entry : page.entries()) {
            LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(VERTICAL);
            if (entry.resource() instanceof MusicDetail track) {
                MusicListItem row = new MusicListItem(getContext());
                row.setShowPusherInfo(false);
                row.bindData(track);
                row.setBackground(ButtonInsetBackgroundFactory.builder().cornerRadius(dp(12)).inset(dp(1))
                        .padding(new ButtonInsetBackgroundFactory.Padding(dp(4), dp(4), dp(4), dp(4)))
                        .build().newBackgroundDrawable());
                row.setOnClickListener(view -> {
                    if (!tasks.isCurrent(token)) return;
                    MusicService.getInstance().sendPushMusicToQueue(track);
                    ToastUtil.show(Toast.makeText(getContext(), text(".text.pushedMusicToPlaylist")
                            + "\n" + track.getName(), Toast.LENGTH_SHORT));
                });
                item.addView(row, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                item.addView(metadata(entry), new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                rows.addView(item, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            } else if (entry.resource() instanceof MusicCollection collection) {
                item.addView(new MusicCollectionCard(getContext(), collection), new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
                TextView metadata = metadata(entry);
                metadata.setMaxWidth(dp(172));
                item.addView(metadata, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                item.setLayoutParams(new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
                collections.addView(item);
            }
        }
    }

    private TextView metadata(TuneWeaveRecentHistory.Entry<?> entry) {
        String time = entry.playedAt() == null ? text(".text.history.unknownTime") : dateFormat.format(entry.playedAt());
        String device = entry.device() == null ? "" : entry.device().displayName();
        TextView view = label(time + (device.isBlank() ? "" : " · " + device));
        view.setMaxLines(2);
        view.setPadding(dp(6), dp(4), dp(6), dp(12));
        view.setTooltipText((entry.playedAt() == null ? time : entry.playedAt().toString())
                + (device.isBlank() ? "" : " · " + device));
        return view;
    }

    private TextView label(String value) {
        TextView view = new TextView(getContext());
        view.setText(value); view.setTextSize(Theme.TEXT_SIZE_NORMAL);
        view.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        return view;
    }
    private Button button(String key, Runnable action) {
        Button button = new Button(getContext());
        button.setText(text(key)); button.setTextColor(Theme.PRIMARY_COLOR);
        button.setTextSize(Theme.TEXT_SIZE_NORMAL);
        button.setBackground(ButtonInsetBackgroundFactory.builder().inset(dp(2))
                .cornerRadius(dp(4)).build().newBackgroundDrawable());
        button.setOnClickListener(view -> action.run());
        return button;
    }
    private static String text(String key) { return I18n.get(MusicHud.MOD_ID + key); }
}
