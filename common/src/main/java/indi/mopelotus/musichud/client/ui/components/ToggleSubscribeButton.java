package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.state.ISubscribeState;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import net.minecraft.client.resources.language.I18n;

public class ToggleSubscribeButton extends AsyncStateToggleButton {
    public ToggleSubscribeButton(Context context) {
        super(context, new Appearance(
                () -> I18n.get(MusicHud.MOD_ID + ".button.unsubscribe"),
                () -> I18n.get(MusicHud.MOD_ID + ".button.subscribe"),
                () -> ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/heart_filled.png"),
                () -> ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/heart.png")));
    }
    public void bindState(ISubscribeState<?> state) {
        if (state == null) bindAsync(null, null, null);
        else bindAsync(state::isSubscribed, selected -> selected ? state.subscribe() : state.unsubscribe(), state::onOthersModify);
    }
}