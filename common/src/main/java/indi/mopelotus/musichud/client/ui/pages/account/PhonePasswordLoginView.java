package indi.mopelotus.musichud.client.ui.pages.account;

import com.google.gson.JsonObject;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.text.method.PasswordTransformationMethod;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.services.LoginService;
import indi.mopelotus.musichud.client.services.tuneweave.*;
import indi.mopelotus.musichud.client.ui.CallbackGeneration;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException;
import net.minecraft.client.resources.language.I18n;
import static icyllis.modernui.view.ViewGroup.LayoutParams.*;

/** Kuwo/Migu client-owned password form and normalized challenge continuation. */
public class PhonePasswordLoginView extends LinearLayout implements ILoginView {
    private static final String[] TYPES = {"username", "phone", "email"};
    private final TuneWeaveClientService service = TuneWeaveClientService.getInstance();
    private final TuneWeavePlatform platform = service.defaultPlatform();
    private final CallbackGeneration callbacks = new CallbackGeneration();
    private final Spinner principalType;
    private final EditText principal, password;
    private final TextView message;
    private final Button login;
    private final LoginVerificationView verification;
    private TuneWeaveLoginAttempt attempt;
    private TuneWeavePasswordSession session;
    private long generation;
    private boolean busy;

    public PhonePasswordLoginView(Context context) {
        super(context); LoginFeaturePolicy.requireEnabled(LoginFeaturePolicy.LoginMethod.PASSWORD);
        setOrientation(VERTICAL); setGravity(Gravity.CENTER_HORIZONTAL);
        if (platform != TuneWeavePlatform.KUWO && platform != TuneWeavePlatform.MIGU)
            throw new IllegalArgumentException("Password form is only available for Kuwo and Migu");
        TextView title = new TextView(context); title.setTextSize(Theme.TEXT_SIZE_LARGE);
        title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR); title.setText(tr("text.login.password")); title.setGravity(Gravity.CENTER); addView(title);
        LinearLayout form = new LinearLayout(context); form.setOrientation(VERTICAL);
        LayoutParams formParams = new LayoutParams(dp(320), WRAP_CONTENT); formParams.topMargin = dp(16); addView(form, formParams);
        principalType = new Spinner(context); principalType.setAdapter(new ArrayAdapter<>(context,
                new String[]{tr("text.login.username"), tr("field.hint.phone"), tr("text.login.email")}));
        form.addView(principalType, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        principal = field(context, "field.hint.loginAccount");
        LayoutParams accountParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT); accountParams.topMargin = dp(8);
        form.addView(principal, accountParams);
        password = field(context, "field.hint.password"); password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        LayoutParams passwordParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT); passwordParams.topMargin = dp(8);
        form.addView(password, passwordParams);
        login = new Button(context); login.setText(tr("button.login")); login.setTextColor(Theme.PRIMARY_COLOR);
        InsetBackgroundFactory.builder().padding(new InsetBackgroundFactory.Padding(dp(16), dp(8), dp(16), dp(8)))
                .cornerRadius(dp(4)).build().applyBackgroundTo(login);
        addView(login); login.setOnClickListener(v -> begin());
        verification = new LoginVerificationView(context, this::advance); form.addView(verification);
        message = new TextView(context); message.setMaxWidth(dp(400)); message.setTextColor(Theme.ERROR_TEXT_COLOR);
        addView(message); message.setVisibility(GONE);
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { generation = callbacks.next(); }
            @Override public void onViewDetachedFromWindow(View v) { reset(); }
        });
    }
    private String tr(String key) { return I18n.get(MusicHud.MOD_ID + "." + key); }
    private EditText field(Context context, String key) {
        EditText field = new EditText(context, null, icyllis.modernui.R.attr.editTextOutlinedStyle);
        field.setSingleLine(); field.setHint(tr(key)); return field;
    }
    private void begin() {
        if (busy) return;
        String account = principal.getText().toString().trim(), secret = password.getText().toString();
        String type = TYPES[Math.clamp(principalType.getSelectedItemPosition(), 0, TYPES.length-1)];
        if (account.isBlank() || account.length()>512 || account.chars().anyMatch(Character::isISOControl)) {
            errorText(tr("text.login.invalidAccount")); return;
        }
        if (type.equals("phone") && !account.matches("1[0-9]{10}")) { errorText(tr("text.phoneFormatError")); return; }
        if (secret.isBlank() || secret.length()>4096) { errorText(tr("text.passwordFormatError")); return; }
        cancel(); verification.clear(); message.setVisibility(GONE);
        TuneWeaveLoginAttempt token = attempt = service.beginLogin(platform); long ticket = generation;
        setBusy(true);
        MusicHud.EXECUTOR.execute(() -> {
            try {
                if (!service.capabilities(platform).contains("password_login")) throw new IllegalStateException(tr("text.login.noSupportedMethod"));
                var next = service.startPasswordLogin(token, type, account, secret);
                callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
                    if (!service.isLoginCurrent(token)) return;
                    session = next; present(token, next.progress());
                });
            } catch (RuntimeException error) { failed(ticket, error); }
        });
    }
    private void advance(JsonObject action) {
        if (busy || session == null || !service.isLoginCurrent(attempt)) return;
        JsonObject body = action.deepCopy();
        if ("submit_image".equals(body.get("action").getAsString())) {
            String secret = password.getText().toString();
            if (secret.isBlank()) { errorText(tr("text.passwordFormatError")); return; }
            body.addProperty("password", secret);
        }
        var captured = session; var token = attempt; long ticket = generation; setBusy(true);
        MusicHud.EXECUTOR.execute(() -> {
            try {
                var progress = service.advancePasswordLogin(captured, body);
                callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
                    if (service.isLoginCurrent(token)) present(token, progress);
                });
            } catch (RuntimeException error) { failed(ticket, error); }
        });
    }
    private void present(TuneWeaveLoginAttempt token, TuneWeaveLoginProgress progress) {
        setBusy(false); message.setVisibility(GONE);
        try {
            if ("confirmed".equals(progress.state())) {
                password.setText(""); LoginService.getInstance().completeTuneWeaveLogin(token, progress.profile()); reset();
            } else { login.setText(tr("button.restartLogin")); verification.render(progress); }
        } catch (java.util.concurrent.CancellationException stale) { reset(); }
        catch (RuntimeException malformed) { reset(); errorText(tr("text.login.failed")); }
    }
    private void failed(long ticket, RuntimeException error) {
        callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
            boolean retry = session != null && service.isLoginCurrent(attempt)
                    && error instanceof TuneWeaveException failure && LoginContinuationPolicy.canContinue(failure);
            if (!retry) { cancel(); verification.clear(); password.setText(""); }
            setBusy(false); errorText(error.getMessage());
        });
    }
    private void setBusy(boolean value) {
        busy = value; login.setEnabled(!value); principalType.setEnabled(!value); principal.setEnabled(!value); password.setEnabled(!value);
    }
    private void cancel() { login.setText(tr("button.login")); generation = callbacks.next(); service.cancelLogin(attempt); attempt = null; session = null; }
    @Override public void reset() { cancel(); setBusy(false); password.setText(""); verification.clear(); message.setVisibility(GONE); }
    @Override public void errorText(String text) { message.setText(text == null || text.isBlank() ? tr("button.loadingError") : text); message.setVisibility(VISIBLE); }
}
