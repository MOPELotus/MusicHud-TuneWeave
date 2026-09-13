package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ClientDistUtil;
import net.minecraft.client.resources.language.I18n;

public class VoteSkipButton extends ToggleIconButton {

    public VoteSkipButton(Context context) {
        super(context, new Appearance(
                () -> ClientDistUtil.getInstance().inSinglePlayer() ? null : I18n.get(MusicHud.MOD_ID + ".text.voted"),
                () -> ClientDistUtil.getInstance().inSinglePlayer()
                        ? I18n.get(MusicHud.MOD_ID + ".button.skip")
                        : I18n.get(MusicHud.MOD_ID + ".button.voteForSkip"),
                () -> ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/skip_forward_filled.png"),
                () -> ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/skip_forward.png")
        ));

        setOnClickListener((v) -> {
            if (isChecked()) {
                MusicService.getInstance().voteForSkipCurrent();
                setEnabled(false);
            }
        });
    }

    public void reset() {
        setChecked(false);
        setEnabled(true);
    }
}
