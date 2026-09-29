package indi.mopelotus.musichud.client.ui.pages.account;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.text.method.PasswordTransformationMethod;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.widget.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.services.LoginService;
import indi.mopelotus.musichud.client.services.tuneweave.*;
import indi.mopelotus.musichud.client.ui.CallbackGeneration;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.PlatformSelector;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import net.minecraft.client.resources.language.I18n;

/** A raw Cookie exists only in the masked input and the direct client-mode import request. */
final class CredentialImportView extends LinearLayout implements ILoginView {
    private final TuneWeaveClientService service = TuneWeaveClientService.getInstance();
    private final CallbackGeneration callbacks = new CallbackGeneration();
    private final EditText input;
    private final TextView message;
    private final Button submit;
    private final PlatformSelector platforms;
    private TuneWeaveLoginAttempt attempt;
    private boolean busy;
    CredentialImportView(Context context) {
        super(context); setOrientation(VERTICAL); setGravity(Gravity.CENTER_HORIZONTAL);
        TextView description = new TextView(context);
        description.setText(I18n.get(MusicHud.MOD_ID + ".text.login.importDescription"));
        description.setTextColor(Theme.SECONDARY_TEXT_COLOR); description.setMaxWidth(dp(360)); addView(description);
        platforms = new PlatformSelector(context, TuneWeavePlatform.SODA, TuneWeavePlatform.MIGU);
        platforms.setSelectedPlatform(service.defaultPlatform()); addView(platforms);
        input = new EditText(context); input.setSingleLine();
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        input.setHint("Cookie"); addView(input, new LayoutParams(dp(320), LayoutParams.WRAP_CONTENT));
        submit = new Button(context); submit.setText(I18n.get(MusicHud.MOD_ID + ".button.login"));
        submit.setOnClickListener(v -> importCookie()); addView(submit);
        message = new TextView(context); message.setTextColor(Theme.ERROR_TEXT_COLOR); addView(message);
        platforms.setOnPlatformSelectedListener(platform -> reset());
    }
    private void importCookie() {
        if (busy || input.getText().toString().isBlank()) return;
        String cookie = input.getText().toString(); input.setText("");
        long ticket = callbacks.next();
        var platform = platforms.getSelectedPlatform();
        var token = attempt = service.beginLogin(platform);
        busy = true; submit.setEnabled(false); platforms.setEnabled(false);
        MusicHud.EXECUTOR.execute(() -> {
            try {
                if (!service.capabilities(platform).contains("credential_import")) throw new IllegalStateException("Unsupported login method");
                var profile = service.importCredential(token, cookie);
                callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
                    if (!isAttachedToWindow()) return;
                    try { LoginService.getInstance().completeTuneWeaveLogin(token, profile); }
                    catch (java.util.concurrent.CancellationException ignored) { }
                    reset();
                });
            } catch (RuntimeException error) {
                callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
                    if (!isAttachedToWindow()) return;
                    reset(); errorText(I18n.get(MusicHud.MOD_ID + ".text.login.failed"));
                });
            }
        });
    }
    @Override protected void onDetachedFromWindow() { reset(); super.onDetachedFromWindow(); }
    @Override public void reset() {
        callbacks.next(); service.cancelLogin(attempt); attempt = null; busy = false;
        input.setText(""); message.setText(""); submit.setEnabled(true); platforms.setEnabled(true);
    }
    @Override public void errorText(String value) { message.setText(value); }
}
