package indi.mopelotus.musichud.client.ui.pages;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.ui.PreferencesFragment;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import net.minecraft.client.resources.language.I18n;
import icyllis.modernui.view.KeyEvent;
import static icyllis.modernui.view.ViewGroup.LayoutParams.*;

/** The CF edition only connects to a service already started by its user. */
final class ApiSettingsUi {
    private ApiSettingsUi() {}

    static void create(Context context, ConfigView owner, LinearLayout view, ServerConfig config) {
        var category = PreferencesFragment.createCategoryList(view,
                I18n.get(MusicHud.MOD_ID + ".config.category.apiServer"));
        var params = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        params.setMargins(0, owner.dp(6), 0, owner.dp(128));
        view.addView(category, params);

        TextView instructions = new TextView(context);
        instructions.setText(I18n.get(MusicHud.MOD_ID + ".text.externalApiSetup"));
        instructions.setTextColor(Theme.NORMAL_TEXT_COLOR);
        instructions.setTextSize(Theme.TEXT_SIZE_NORMAL);
        category.addView(instructions);

        var box = ConfigView.createFullWidthInputBox(context,
                I18n.get(MusicHud.MOD_ID + ".config.apiServer.serverApiBaseUrl"));
        EditText input = box.findViewById(R.id.input);
        input.setText(ClientConfig.getInstance().getTuneWeaveBaseUrl());
        input.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEY_ENTER) { input.clearFocus(); return true; }
            return false;
        });
        input.setOnFocusChangeListener((v, focused) -> {
            if (!focused && !ApiConnectionSettings.apply(input.getText().toString(), ClientConfig.getInstance(), config)) {
                input.setText(ClientConfig.getInstance().getTuneWeaveBaseUrl());
                ToastUtil.show(I18n.get(MusicHud.MOD_ID + ".text.invalidServiceUrl"));
            }
        });
        category.addView(box);

        String template = I18n.get(MusicHud.MOD_ID + ".text.apiServiceVersion");
        TextView version = new TextView(context);
        version.setTextSize(14);
        ConfigView.updateTuneWeaveVersionLabel(version, template);
        category.addView(version);
        Button check = new Button(context);
        check.setText(I18n.get(MusicHud.MOD_ID + ".button.checkApiServerVersion"));
        check.setTextColor(Theme.PRIMARY_COLOR);
        check.setOnClickListener(v -> ConfigView.updateTuneWeaveVersionLabel(version, template));
        category.addView(check);
    }
}
