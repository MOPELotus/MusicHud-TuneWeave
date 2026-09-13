package indi.mopelotus.musichud.client.ui.pages.account;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.text.method.PasswordTransformationMethod;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.services.LoginService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.PlatformSelector;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import net.minecraft.client.resources.language.I18n;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Direct client-mode password login. The password is sent only to TuneWeave over this client request. */
public class PhonePasswordLoginView extends LinearLayout implements ILoginView {
    private final PlatformSelector platformSelector;
    private final EditText regionInput;
    private final EditText phoneInput;
    private final EditText passwordInput;
    private final Button loginButton;
    private final TextView messageTextView;
    private final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();

    public PhonePasswordLoginView(Context context) {
        super(context);
        LoginFeaturePolicy.requireEnabled(LoginFeaturePolicy.LoginMethod.PASSWORD);
        setOrientation(LinearLayout.VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(context);
        title.setTextSize(Theme.TEXT_SIZE_LARGE);
        title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        title.setText(I18n.get(MusicHud.MOD_ID + ".text.login.password"));
        addView(title, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        TextView description = new TextView(context);
        description.setTextSize(Theme.TEXT_SIZE_NORMAL);
        description.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        description.setText(I18n.get(MusicHud.MOD_ID + ".text.login.description"));
        LayoutParams descriptionParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        descriptionParams.setMargins(0, dp(4), 0, 0);
        addView(description, descriptionParams);

        platformSelector = new PlatformSelector(context, TuneWeavePlatform.NETEASE, TuneWeavePlatform.QQ);
        platformSelector.setSelectedPlatform(tuneWeave.defaultPlatform());
        LayoutParams platformParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        platformParams.setMargins(0, dp(12), 0, 0);
        addView(platformSelector, platformParams);

        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setGravity(Gravity.CENTER_HORIZONTAL);
        form.setLayoutParams(new LayoutParams(dp(320), WRAP_CONTENT));
        addView(form);

        LinearLayout phoneRow = new LinearLayout(context);
        phoneRow.setOrientation(LinearLayout.HORIZONTAL);
        phoneRow.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams phoneRowParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        phoneRowParams.setMargins(0, dp(24), 0, 0);
        form.addView(phoneRow, phoneRowParams);

        TextView plus = new TextView(context);
        plus.setText("+");
        plus.setTextSize(Theme.TEXT_SIZE_LARGE);
        plus.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        plus.setGravity(Gravity.CENTER);
        phoneRow.addView(plus, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        regionInput = new EditText(context, null, R.attr.editTextStyle);
        regionInput.setSingleLine();
        regionInput.setText("86");
        phoneRow.addView(regionInput, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        phoneInput = new EditText(context, null, R.attr.editTextOutlinedStyle);
        phoneInput.setSingleLine();
        phoneInput.setHint(I18n.get(MusicHud.MOD_ID + ".field.hint.phone"));
        LinearLayout.LayoutParams phoneParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        phoneParams.setMargins(dp(8), 0, 0, dp(2));
        phoneRow.addView(phoneInput, phoneParams);

        passwordInput = new EditText(context, null, R.attr.editTextOutlinedStyle);
        passwordInput.setSingleLine();
        passwordInput.setHint(I18n.get(MusicHud.MOD_ID + ".field.hint.password"));
        passwordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        LayoutParams passwordParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        passwordParams.setMargins(0, dp(8), 0, dp(2));
        form.addView(passwordInput, passwordParams);

        loginButton = new Button(context);
        loginButton.setText(I18n.get(MusicHud.MOD_ID + ".button.login"));
        loginButton.setTextColor(Theme.PRIMARY_COLOR);
        loginButton.setBackground(ButtonInsetBackgroundFactory.builder()
                .padding(new ButtonInsetBackgroundFactory.Padding(dp(16), dp(8), dp(16), dp(8)))
                .cornerRadius(dp(4)).inset(dp(1)).build().newBackgroundDrawable());
        LayoutParams loginParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        loginParams.setMargins(0, dp(16), 0, 0);
        form.addView(loginButton, loginParams);

        messageTextView = new TextView(context);
        messageTextView.setTextSize(Theme.TEXT_SIZE_NORMAL);
        messageTextView.setMaxWidth(dp(400));
        messageTextView.setMinHeight(36);
        messageTextView.setSingleLine(false);
        messageTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        messageTextView.setVisibility(GONE);
        LayoutParams messageParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        messageParams.setMargins(0, dp(8), 0, 0);
        form.addView(messageTextView, messageParams);

        loginButton.setOnClickListener(view -> login());
    }

    private void login() {
        String region = regionInput.getText().toString().trim();
        String phone = phoneInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (!region.matches("\\d{1,4}")) {
            errorText(I18n.get(MusicHud.MOD_ID + ".text.regionCodeFormatError"));
            return;
        }
        if (!phone.matches("\\d{5,20}")) {
            errorText(I18n.get(MusicHud.MOD_ID + ".text.phoneFormatError"));
            return;
        }
        if (password.isBlank()) {
            errorText(I18n.get(MusicHud.MOD_ID + ".text.passwordFormatError"));
            return;
        }
        setBusy(true);
        TuneWeavePlatform platform = selectedPlatform();
        MusicHud.EXECUTOR.execute(() -> {
            try {
                LoginService.getInstance().completeTuneWeaveLogin(
                        tuneWeave.loginWithPassword(platform, "phone", phone, password, null, region));
            } catch (RuntimeException error) {
                MuiModApi.postToUiThread(() -> {
                    setBusy(false);
                    errorText(error.getMessage());
                });
            }
        });
    }

    private TuneWeavePlatform selectedPlatform() {
        return platformSelector.getSelectedPlatform();
    }

    private void setBusy(boolean busy) {
        MuiModApi.postToUiThread(() -> {
            loginButton.setClickable(!busy);
            loginButton.setAlpha(busy ? 0.55f : 1f);
            platformSelector.setEnabled(!busy);
            regionInput.setClickable(!busy);
            phoneInput.setClickable(!busy);
            passwordInput.setClickable(!busy);
        });
    }

    @Override
    public void reset() {
        setBusy(false);
        messageTextView.setVisibility(GONE);
    }

    @Override
    public void errorText(String message) {
        messageTextView.setTextColor(Theme.ERROR_TEXT_COLOR);
        messageTextView.setText(message == null || message.isBlank()
                ? I18n.get(MusicHud.MOD_ID + ".button.loadingError") : message);
        messageTextView.setVisibility(View.VISIBLE);
    }
}
