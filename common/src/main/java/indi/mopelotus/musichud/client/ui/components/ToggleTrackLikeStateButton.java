package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.state.IMusicTrackState;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import net.minecraft.client.resources.language.I18n;

public class ToggleTrackLikeStateButton extends AsyncStateToggleButton {
    protected IMusicTrackState.IPlaylistSubState playlistSubState;
    public ToggleTrackLikeStateButton(Context context) {
        super(context, new Appearance(
                () -> I18n.get(MusicHud.MOD_ID + ".button.toggleMusicLike.remove"),
                () -> I18n.get(MusicHud.MOD_ID + ".button.toggleMusicLike.add"),
                () -> ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/heart_filled.png"),
                () -> ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/heart.png")));
    }
    public void bindMusicList(IMusicTrackState.IPlaylistSubState state) {
        playlistSubState = state;
        if (state == null) bindAsync(null, null, null);
        else bindAsync(state::isContained, selected -> selected ? state.add() : state.remove(), state::onOthersModify);
    }
}