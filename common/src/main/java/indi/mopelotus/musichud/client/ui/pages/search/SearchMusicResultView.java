package indi.mopelotus.musichud.client.ui.pages.search;

import icyllis.modernui.core.Context;
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
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import lombok.Getter;
import net.minecraft.client.resources.language.I18n;

import java.util.List;
import java.util.stream.Collectors;

public class SearchMusicResultView extends LinearLayout {
    @Getter
    private static SearchMusicResultView instance;
    private static final SearchResultBuffer<MusicDetail> results = new SearchResultBuffer<>(MusicDetail::getSourceRef);

    public SearchMusicResultView(Context context) {
        super(context);
        instance = this;
        setOrientation(LinearLayout.VERTICAL);
        refresh();
    }

    public static void setResult(List<MusicDetail> result) {
        results.replace(result);
        if (instance != null) {
            instance.refresh();
        }
    }

    public void refresh() {
        List<MusicDetail> result = results.snapshot();
        removeAllViews();
        if (result != null) {
            for (MusicDetail musicDetail : result) {
                addItem(getContext(), musicDetail);
            }
        }
    }

    public void append(List<MusicDetail> page) {
        for (MusicDetail item : results.append(page)) addItem(getContext(), item);
    }

    private void addItem(Context context, MusicDetail musicDetail) {
        var musicLayout = new MusicListItem(context);
        musicLayout.bindData(musicDetail);
        var background = ButtonInsetBackgroundFactory.builder()
                .cornerRadius(dp(12))
                .inset(dp(1))
                .padding(new ButtonInsetBackgroundFactory.Padding(dp(4), dp(4), dp(4), dp(4))).build().newBackgroundDrawable();
        musicLayout.setBackground(background);

        musicLayout.setClickable(true);
        String artistsName = musicDetail.getArtists().stream()
                .map(Artist::getName).collect(Collectors.joining(" / "));
        musicLayout.setOnClickListener((view) -> {
            if ("video".equals(musicDetail.getSourceKind())) {
                RouterContainer.getInstance().pushNavigate(new VideoDetailView(context, musicDetail));
                return;
            }
            MusicService.getInstance().sendPushMusicToQueue(musicDetail);
            ToastUtil.show(Toast.makeText(context, I18n.get(MusicHud.MOD_ID + ".text.pushedMusicToPlaylist") + "\n" + musicDetail.getName() + " - " + artistsName, Toast.LENGTH_SHORT));
        });
        addView(musicLayout, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }
}
