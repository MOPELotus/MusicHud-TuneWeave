package indi.mopelotus.musichud.client.ui.pages.account;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.services.LoginService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveChallengeSession;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveLoginAttempt;
import indi.mopelotus.musichud.client.ui.CallbackGeneration;
import indi.mopelotus.musichud.client.ui.components.PlatformSelector;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import net.minecraft.client.resources.language.I18n;

import java.time.Duration;
import java.time.ZonedDateTime;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public class PhoneCodeLoginView extends LinearLayout implements ILoginView {
    private indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveBrowserVerification browser;
    private CheckBox allowAccountCreation;
    private LoginVerificationView verificationView;
    private indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveLoginProgress progress;
    private final EditText phoneTextInput;
    private final EditText codeTextInput;
    private final TextView messageTextView;
    private final EditText phoneRegionInput;
    private final Button sendCodeButton;
    private final PlatformSelector platformSelector;
    private final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();
    private ZonedDateTime lastSentCodeTime;
    private Button loginButton;
    private TuneWeaveLoginAttempt attempt;
    private SmsLoginInput submittedInput;
    private final CallbackGeneration callbacks = new CallbackGeneration();
    private long generation;
    private boolean sending, verifying;
    MusicHud.ScheduledTask scheduledRefreshTask = null;
    private volatile TuneWeaveChallengeSession challengeSession;

    public PhoneCodeLoginView(Context context) {
        super(context);
        setOrientation(LinearLayout.VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        TextView textView = new TextView(context);
        textView.setTextSize(Theme.TEXT_SIZE_LARGE);
        textView.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        textView.setText(I18n.get(MusicHud.MOD_ID + ".text.login.deviceCode"));
        textView.setLayoutParams(new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        addView(textView);

        TextView textView1 = new TextView(context);
        textView1.setTextSize(Theme.TEXT_SIZE_NORMAL);
        textView1.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        textView1.setText(I18n.get(MusicHud.MOD_ID + ".text.login.description"));
        LayoutParams params1 = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params1.setMargins(0, dp(4), 0, 0);
        textView1.setLayoutParams(params1);
        addView(textView1);

        platformSelector = new PlatformSelector(context, TuneWeavePlatform.NETEASE, TuneWeavePlatform.QQ, TuneWeavePlatform.KUGOU, TuneWeavePlatform.KUWO, TuneWeavePlatform.MIGU);
        platformSelector.setSelectedPlatform(tuneWeave.defaultPlatform());
        platformSelector.setVisibility(GONE);
        LayoutParams platformParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        platformParams.setMargins(0, dp(12), 0, 0);
        addView(platformSelector, platformParams);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new LayoutParams(dp(320), WRAP_CONTENT));
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(content);
        allowAccountCreation = new CheckBox(context);
        allowAccountCreation.setText(I18n.get(MusicHud.MOD_ID + ".text.login.allowAccountCreation"));
        allowAccountCreation.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        content.addView(allowAccountCreation, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        updateAccountCreationOption();

        LinearLayout layout1 = new LinearLayout(context);
        layout1.setOrientation(LinearLayout.HORIZONTAL);
        LayoutParams layout1p = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        layout1p.setMargins(0, dp(32), 0, 0);
        layout1.setLayoutParams(layout1p);
        content.addView(layout1);

        LinearLayout layout2 = new LinearLayout(context);
        layout2.setOrientation(LinearLayout.HORIZONTAL);
        LayoutParams layout2p = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        layout2p.setMargins(0, dp(8), 0, 0);
        layout2.setLayoutParams(layout2p);
        content.addView(layout2);

        TextView plus = new TextView(context);
        plus.setTextSize(Theme.TEXT_SIZE_LARGE);
        plus.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        plus.setText("+");
        plus.setTextAlignment(TEXT_ALIGNMENT_CENTER);
        LayoutParams params2 = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 0);
        params2.gravity = Gravity.CENTER;
        layout1.addView(plus, params2);

        phoneRegionInput = new EditText(context, null, R.attr.editTextStyle);
        phoneRegionInput.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        phoneRegionInput.setLayoutParams(new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 0));
        phoneRegionInput.setSingleLine();
        phoneRegionInput.setText("86");
        layout1.addView(phoneRegionInput);

        phoneTextInput = new EditText(context, null, R.attr.editTextOutlinedStyle);
        phoneTextInput.setTextAlignment(TEXT_ALIGNMENT_TEXT_START);
        phoneTextInput.setHint(I18n.get(MusicHud.MOD_ID + ".field.hint.phone"));
        phoneTextInput.setSingleLine();
        LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1);
        params.setMargins(0, 0, 0, dp(2));
        layout1.addView(phoneTextInput, params);

        codeTextInput = new EditText(context, null, R.attr.editTextOutlinedStyle);
        codeTextInput.setTextAlignment(TEXT_ALIGNMENT_TEXT_START);
        codeTextInput.setHint(I18n.get(MusicHud.MOD_ID + ".field.hint.code"));
        codeTextInput.setSingleLine();
        LayoutParams codeP = new LayoutParams(0, WRAP_CONTENT, 1);
        codeP.setMargins(0, 0, 0, dp(2));
        layout2.addView(codeTextInput, codeP);

        var bf1 = InsetBackgroundFactory.builder()
                .padding(new InsetBackgroundFactory.Padding(dp(8), dp(8), dp(8), dp(8)))
                .cornerRadius(dp(4))
                .build();

        sendCodeButton = new Button(context);
        bf1.applyBackgroundTo(sendCodeButton);
        sendCodeButton.setText(I18n.get(MusicHud.MOD_ID + ".button.sendCode"));
        LayoutParams params3 = new LayoutParams(WRAP_CONTENT, MATCH_PARENT, 0);
        params3.setMargins(dp(8), 0, 0, 0);
        sendCodeButton.setLayoutParams(params3);
        sendCodeButton.setOnClickListener(v -> sendCode());
        layout2.addView(sendCodeButton);

        loginButton = new Button(context);
        loginButton.setText(I18n.get(MusicHud.MOD_ID + ".button.login"));
        LayoutParams loginP = new LayoutParams(dp(128), WRAP_CONTENT);
        loginP.setMargins(0, dp(16), 0, 0);
        loginButton.setLayoutParams(loginP);
        loginButton.setOnClickListener(v -> verifyCode());
        var bf2 = InsetBackgroundFactory.builder()
                .padding(new InsetBackgroundFactory.Padding(dp(16), dp(8), dp(16), dp(8)))
                .cornerRadius(dp(4))
                .build();
        bf2.applyBackgroundTo(loginButton);
        content.addView(loginButton);
        verificationView = new LoginVerificationView(context, this::advanceChallenge);
        content.addView(verificationView, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        messageTextView = new TextView(context);
        messageTextView.setTextSize(Theme.TEXT_SIZE_NORMAL);
        messageTextView.setMaxWidth(dp(400));
        messageTextView.setMinHeight(36);
        messageTextView.setSingleLine(false);
        LayoutParams messageParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        messageParams.setMargins(0, dp(8), 0, 0);
        messageTextView.setLayoutParams(messageParams);
        messageTextView.setVisibility(View.GONE);
        messageTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(messageTextView, messageParams);

        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) { reset(); }

            @Override
            public void onViewDetachedFromWindow(View view) {
                invalidateLogin();
            }
        });
        platformSelector.setOnPlatformSelectedListener(platform -> { reset(); allowAccountCreation.setChecked(true); updateAccountCreationOption(); });
    }

    private void updateAccountCreationOption() {
        var platform = selectedPlatform();
        allowAccountCreation.setChecked(true);
        allowAccountCreation.setVisibility(GONE);
    }

    private void setSendingButtonDisable() {
        sendCodeButton.setAlpha(0.5F);
        sendCodeButton.setClickable(false);
    }

    private void setSendingButtonEnable() {
        sendCodeButton.setAlpha(1.0F);
        sendCodeButton.setClickable(true);
    }

    private TuneWeavePlatform selectedPlatform() {
        return platformSelector.getSelectedPlatform();
    }

    private void sendCode() {
        if (sending || verifying || scheduledRefreshTask != null) return;
        SmsLoginInput input;
        try { input = new SmsLoginInput(phoneTextInput.getText().toString().trim(), phoneRegionInput.getText().toString().trim()); }
        catch (IllegalArgumentException error) { errorText(I18n.get(MusicHud.MOD_ID + ".text.phoneFormatError")); return; }
        boolean allowCreation = selectedPlatform() == TuneWeavePlatform.KUWO || selectedPlatform() == TuneWeavePlatform.KUGOU;
        if (selectedPlatform() == TuneWeavePlatform.KUWO && !allowCreation) {
            errorText(I18n.get(MusicHud.MOD_ID + ".text.login.kuwoCreationRequired")); return;
        }
        invalidateLogin();
        submittedInput = input;
        var platform = selectedPlatform();
        TuneWeaveLoginAttempt token = attempt = tuneWeave.beginLogin(platform);
        long ticket = generation;
        sending = true;
        setSendingButtonDisable();
        MusicHud.EXECUTOR.execute(() -> {
            try {
                if (!tuneWeave.capabilities(platform).contains("phone_login")) throw new IllegalStateException("Unsupported login method");
                var challenge = tuneWeave.startSmsLogin(token, input.phone(), input.countryCode(), allowCreation);
                callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
                    sending = false;
                    if (!tuneWeave.isLoginCurrent(token)) { reset(); return; }
                    challengeSession = challenge;
                    showProgress(challenge.progress(), ticket);

                });
            } catch (RuntimeException error) { failed(ticket, error); }
        });
    }

    private void verifyCode() {
        if (sending || verifying) return;
        String code;
        try {
            var current = new SmsLoginInput(phoneTextInput.getText().toString().trim(), phoneRegionInput.getText().toString().trim());
            if (!current.equals(submittedInput)) throw new IllegalArgumentException("Phone changed");
            code = SmsLoginInput.code(codeTextInput.getText().toString());
        } catch (IllegalArgumentException error) { errorText(I18n.get(MusicHud.MOD_ID + ".text.codeFormatError")); return; }
        var body = new com.google.gson.JsonObject(); body.addProperty("code", code);
        advanceChallenge(body);
    }

    private void advanceChallenge(com.google.gson.JsonObject body) {
        if (sending || verifying) return;
        var session = challengeSession;
        var token = attempt;
        if (session == null || session.platform() != selectedPlatform() || !tuneWeave.isLoginCurrent(token)) {
            errorText(I18n.get(MusicHud.MOD_ID + ".text.sendCodeFirst")); return;
        }
        String action = body.has("action") ? body.get("action").getAsString() : "submit_code";
        if ("open_browser".equals(action)) { openBrowserVerification(); return; }
        if ("select_account".equals(action) || "submit_browser".equals(action)) {
            try { body.addProperty("code", SmsLoginInput.code(codeTextInput.getText().toString())); }
            catch (IllegalArgumentException error) { errorText(I18n.get(MusicHud.MOD_ID + ".text.codeFormatError")); return; }
        }
        long ticket = generation;
        verifying = true;
        loginButton.setClickable(false);
        MusicHud.EXECUTOR.execute(() -> {
            try {
                var result = tuneWeave.advanceSmsLogin(session, body);
                callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
                    verifying = false;
                    loginButton.setClickable(true);
                    if (!tuneWeave.isLoginCurrent(token)) { reset(); return; }
                    if ("confirmed".equals(result.state())) {
                        try { LoginService.getInstance().completeTuneWeaveLogin(token, result.profile()); }
                        catch (java.util.concurrent.CancellationException ignored) { }
                        reset();
                    } else showProgress(result, ticket);
                });
            } catch (RuntimeException error) { failed(ticket, error); }
        });
    }

    private void showProgress(indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveLoginProgress value, long ticket) {
        progress = value;
        verificationView.render(value);
        loginButton.setVisibility("waiting".equals(value.state()) ? VISIBLE : GONE);
        if ("waiting".equals(value.state())) {
            if (scheduledRefreshTask == null) { lastSentCodeTime = ZonedDateTime.now(); startCountdown(60, ticket); }
            messageTextView.setText(I18n.get(MusicHud.MOD_ID + ".text.login.smsSent"));
            messageTextView.setTextColor(Theme.SECONDARY_TEXT_COLOR); messageTextView.setVisibility(VISIBLE);
        }
    }

    private void openBrowserVerification() {
        if (browser != null || progress == null) return;
        long ticket = generation;
        try {
            var bridge = new indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveBrowserVerification(progress.verification());
            browser = bridge;
            MusicHud.EXECUTOR.execute(() -> {
                try { bridge.openBrowser(); }
                catch (Exception error) { bridge.close(); failed(ticket, new IllegalStateException("Cannot open verification browser")); }
            });
            bridge.receipt().whenComplete((response, error) -> callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
                if (browser != bridge) return;
                browser = null;
                if (error == null) {
                    var body = LoginVerificationView.action("submit_browser");
                    body.addProperty("verification_id", bridge.verificationId()); body.addProperty("response", response);
                    bridge.close(); advanceChallenge(body);
                } else { bridge.close(); errorText(I18n.get(MusicHud.MOD_ID + ".text.login.failed")); }
            }));
        } catch (Exception error) { errorText(I18n.get(MusicHud.MOD_ID + ".text.login.failed")); }
    }

    private void failed(long ticket, RuntimeException error) {
        callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
            boolean retry = error instanceof indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException failure
                    && LoginContinuationPolicy.canContinue(failure) && challengeSession != null && tuneWeave.isLoginCurrent(attempt);
            if (retry) {
                sending = false; verifying = false; loginButton.setClickable(true);
                if (progress != null && "browser_verification_required".equals(progress.state())) {
                    progress = new indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveLoginProgress("waiting", null, java.util.List.of(), null);
                    showProgress(progress, ticket);
                }
            }
            else reset();
            if (!(error instanceof java.util.concurrent.CancellationException))
                errorText(I18n.get(MusicHud.MOD_ID + ".text.login.failed"));
        });
    }

    private void startCountdown(int timeout, long ticket) {
        scheduledRefreshTask = MusicHud.scheduleWithFixedDelay(() -> callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
            long seconds = timeout - Duration.between(lastSentCodeTime, ZonedDateTime.now()).getSeconds();
            if (seconds <= 0) {
                setSendingButtonEnable();
                sendCodeButton.setText(I18n.get(MusicHud.MOD_ID + ".button.sendCode"));
                if (scheduledRefreshTask != null) { scheduledRefreshTask.stop(); scheduledRefreshTask = null; }
            } else sendCodeButton.setText(String.valueOf(seconds));
        }), Duration.ZERO, Duration.ofSeconds(1));
    }

    private void invalidateLogin() {
        generation = callbacks.next();
        if (browser != null) { var previous = browser; browser = null; previous.close(); }
        tuneWeave.cancelLogin(attempt); attempt = null;
        challengeSession = null; submittedInput = null; progress = null;
        if (verificationView != null) verificationView.clear();
        if (scheduledRefreshTask != null) { scheduledRefreshTask.stop(); scheduledRefreshTask = null; }
        sending = false; verifying = false;
    }

    @Override public void reset() {
        invalidateLogin();
        setSendingButtonEnable();
        sendCodeButton.setText(I18n.get(MusicHud.MOD_ID + ".button.sendCode"));
        loginButton.setClickable(true);
        loginButton.setVisibility(VISIBLE);
        messageTextView.setVisibility(GONE);
    }

    @Override public void errorText(String message) {
        messageTextView.setTextColor(Theme.ERROR_TEXT_COLOR);
        messageTextView.setVisibility(View.VISIBLE);
        messageTextView.setText(message);
    }
}
