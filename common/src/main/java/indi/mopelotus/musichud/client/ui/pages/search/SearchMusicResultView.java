package indi.mopelotus.musichud.client.ui.pages.search;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.ViewGroup;
import indi.mopelotus.musichud.client.ui.layouts.ViewportListLayout;
import indi.mopelotus.musichud.client.ui.layouts.VirtualizedListLayout;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.Toast;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import indi.mopelotus.musichud.client.ui.components.MusicListItem;
import indi.mopelotus.musichud.client.ui.components.RouterContainer;
import indi.mopelotus.musichud.client.ui.pages.VideoDetailView;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import lombok.Getter;
import net.minecraft.client.resources.language.I18n;

import java.util.List;
import java.util.stream.Collectors;

public class SearchMusicResultView extends ViewportListLayout<MusicDetail, MusicListItem> {
    @Getter private static SearchMusicResultView instance;
    private static final SearchResultBuffer<MusicDetail> results = new SearchResultBuffer<>(MusicDetail::getSourceRef);
    public SearchMusicResultView(Context context) {
        super(context, new VirtualizedListLayout.Adapter<>() {
            public long idOf(MusicDetail music) { return music.getId(); }
            public MusicListItem createItem(ViewGroup parent) {
                var item = new MusicListItem(context);
                InsetBackgroundFactory.builder().cornerRadius(item.dp(12)).inset(item.dp(1))
                        .padding(new InsetBackgroundFactory.Padding(item.dp(4), item.dp(4), item.dp(4), item.dp(4)))
                        .build().applyBackgroundTo(item);
                return item;
            }
            public void clearItem(MusicListItem item) { item.clearData(); item.setOnClickListener(null); }
            public long boundIdOf(MusicListItem item) { return item.getMusicDetail() == null ? -1 : item.getMusicDetail().getId(); }
            public void bindItem(MusicListItem item, MusicDetail music) {
                Object account = indi.mopelotus.musichud.client.services.music.MusicEntityCache.captureGeneration();
                item.bindData(music);
                item.setClickable(true);
                item.setOnClickListener(view -> {
                    if (account != indi.mopelotus.musichud.client.services.music.MusicEntityCache.captureGeneration()) return;
                    if ("video".equals(music.getSourceKind())) {
                        RouterContainer.getInstance().pushNavigate(new VideoDetailView(context, music)); return;
                    }
                    MusicService.getInstance().sendPushMusicToQueue(music);
                    String artists = music.getArtists().stream().map(Artist::getName).collect(Collectors.joining(" / "));
                    ToastUtil.show(Toast.makeText(context, I18n.get(MusicHud.MOD_ID + ".text.pushedMusicToPlaylist")
                            + "\n" + music.getName() + " - " + artists, Toast.LENGTH_SHORT));
                });
            }
        });
        setDefaultItemHeight(dp(72)); setAnimationsEnabled(false);
        instance = this; refresh();
    }
    public static void setResult(List<MusicDetail> values) {
        results.replace(values); if (instance != null) instance.refresh();
    }
    public void refresh() { resetItems(results.snapshot() == null ? List.of() : results.snapshot()); }
    public void append(List<MusicDetail> page) { results.append(page); syncItems(results.snapshot()); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); instance = this; refresh(); }
    @Override protected void onDetachedFromWindow() { if (instance == this) instance = null; super.onDetachedFromWindow(); }
}
