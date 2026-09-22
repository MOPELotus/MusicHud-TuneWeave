package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Toast;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import net.minecraft.client.resources.language.I18n;

import java.util.function.Function;
import java.util.stream.Collectors;

public class MusicListFactory {
    public static MusicListItem createItem(ViewGroup parent) {
        return createItem(parent, (view) -> false);
    }
    public static MusicListItem createItem(ViewGroup parent, Function<View, Boolean> clickFilter) {
        MusicListItem item = new MusicListItem(parent.getContext());
        item.setRowAnimationsEnabled(false);
        item.setShowPusherInfo(false);
        if (item.getBackground() == null) {
            InsetBackgroundFactory.builder()
                    .cornerRadius(item.dp(7))
                    .inset(item.dp(1))
                    .padding(new InsetBackgroundFactory.Padding(item.dp(4), item.dp(4), item.dp(4), item.dp(4)))
                    .build()
                    .applyBackgroundTo(item);
        }
        item.setClickable(true);
        Context context = parent.getContext();
        item.setOnClickListener(view -> {
            if (clickFilter.apply(view)) return;
            MusicDetail musicDetail = item.getMusicDetail();
            if (musicDetail == null) return;
            MusicService.getInstance().sendPushMusicToQueue(musicDetail);
            String artistsName = musicDetail.getArtists().stream()
                    .map(Artist::getName).collect(Collectors.joining(" / "));
            ToastUtil.show(Toast.makeText(context, I18n.get(MusicHud.MOD_ID + ".text.pushedMusicToPlaylist") + "\n" + musicDetail.getName() + " - " + artistsName, Toast.LENGTH_SHORT));
        });
        return item;
    }
}
