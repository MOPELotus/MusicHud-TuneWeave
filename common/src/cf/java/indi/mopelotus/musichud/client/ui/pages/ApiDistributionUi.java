package indi.mopelotus.musichud.client.ui.pages;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.Modal;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import net.minecraft.client.resources.language.I18n;

/** Local setup instructions; this edition has no binary acquisition code. */
public final class ApiDistributionUi {
    private ApiDistributionUi() {}

    public static View create(Context context, ButtonInsetBackgroundFactory backgroundFactory,
                              EditText[] executableInput) {
        Button button = new Button(context);
        button.setText(I18n.get(MusicHud.MOD_ID + ".button.localApiSetup"));
        button.setTextColor(Theme.PRIMARY_COLOR);
        button.setTextSize(14);
        button.setBackground(backgroundFactory.newBackgroundDrawable());
        button.setOnClickListener(v -> {
            TextView instructions = new TextView(context);
            instructions.setText(I18n.get(MusicHud.MOD_ID + ".text.localApiSetup"));
            instructions.setTextSize(Theme.TEXT_SIZE_NORMAL);
            instructions.setTextColor(Theme.NORMAL_TEXT_COLOR);
            new Modal(context, instructions,
                    new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.localApiSetupClose"),
                            (action, modal) -> modal.dismiss())).show();
        });
        return button;
    }
}
