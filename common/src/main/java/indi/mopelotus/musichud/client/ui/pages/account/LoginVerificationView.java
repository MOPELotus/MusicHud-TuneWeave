package indi.mopelotus.musichud.client.ui.pages.account;

import com.google.gson.JsonObject;
import icyllis.modernui.core.Context;
import icyllis.modernui.widget.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveLoginProgress;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.UrlImageView;
import net.minecraft.client.resources.language.I18n;
import java.util.function.Consumer;

/** Reuses the normal form controls for normalized, user-completed authentication challenges. */
final class LoginVerificationView extends LinearLayout {
    private final Consumer<JsonObject> submit;
    LoginVerificationView(Context context, Consumer<JsonObject> submit) {
        super(context); this.submit = submit; setOrientation(VERTICAL); setVisibility(GONE);
    }
    void clear() { removeAllViews(); setVisibility(GONE); }
    void render(TuneWeaveLoginProgress progress) {
        clear();
        if (progress == null) return;
        JsonObject verification = presentation(progress.verification());
        switch (progress.state()) {
            case "account_selection_required" -> {
                label("selectLoginAccount");
                for (var account : progress.accounts()) {
                    Button button = new Button(getContext());
                    button.setText(account.nickname() + " · " + account.userId());
                    button.setOnClickListener(v -> {
                        var body = action("select_account"); body.addProperty("user_id", account.userId()); submit.accept(body);
                    });
                    addView(button);
                }
            }
            case "verification_required" -> {
                if (verification.has("image_data_url")) {
                    String image = string(verification, "image_data_url");
                    if (!image.startsWith("data:image/jpeg;base64,") && !image.startsWith("data:image/png;base64,"))
                        throw new IllegalArgumentException("Unsupported verification image");
                    label("imageVerification");
                    UrlImageView view = new UrlImageView(getContext());
                    view.setAspectRatio(0); view.setSquareCrop(false); view.setCornerRadius(0);
                    addView(view, new LayoutParams(dp(240), dp(100))); view.loadUrl(image);
                    EditText answer = input("imageAnswer");
                    button("submitVerification", () -> {
                        var body = action("submit_image"); body.addProperty("answer", answer.getText().toString().trim()); submit.accept(body);
                    });
                    long refreshAt = System.nanoTime() + waitSeconds(verification, "refresh_after_secs", 2) * 1_000_000_000L;
                    button("refreshVerification", () -> { if (System.nanoTime() >= refreshAt) submit.accept(action("refresh_image")); });
                } else if ("sms".equals(string(verification, "method")) || "voice".equals(string(verification, "method"))) {
                    String method = string(verification, "method");
                    label(method.equals("voice") ? "voiceVerification" : "additionalVerification");
                    String destination = string(verification, "masked_destination");
                    if (!destination.isBlank()) text(destination);
                    EditText code = input("code");
                    button("submitVerification", () -> {
                        var body = action("submit_" + method); body.addProperty("code", code.getText().toString().trim()); submit.accept(body);
                    });
                    long resendAt = System.nanoTime() + waitSeconds(verification, "resend_after_secs", 60) * 1_000_000_000L;
                    button(method.equals("voice") ? "resendVoice" : "sendCode", () -> {
                        if (System.nanoTime() >= resendAt) submit.accept(action("resend_" + method));
                    });
                } else if (verification.has("methods") && verification.get("methods").isJsonArray()) {
                    label("additionalVerification");
                    String destination = string(verification, "masked_destination");
                    if (!destination.isBlank()) text(destination);
                    for (var method : verification.getAsJsonArray("methods")) {
                        if (!method.isJsonPrimitive() || !method.getAsJsonPrimitive().isString()) continue;
                        if (method.getAsString().equals("sms")) {
                            long resendAt = System.nanoTime() + waitSeconds(verification, "resend_after_secs", 0) * 1_000_000_000L;
                            button("sendCode", () -> { if (System.nanoTime() >= resendAt) submit.accept(action("send_sms")); });
                            EditText code = input("code");
                            button("submitVerification", () -> {
                                var body = action("submit_sms"); body.addProperty("code", code.getText().toString()); submit.accept(body);
                            });
                        }
                        if (method.getAsString().equals("up_sms") && verification.has("up_sms") && verification.get("up_sms").isJsonObject()) {
                            var sms = verification.getAsJsonObject("up_sms");
                            text(I18n.get(MusicHud.MOD_ID + ".text.login.upSmsInstructions", string(sms, "destination"), string(sms, "message")));
                            button("verifySentSms", () -> submit.accept(action("verify_up_sms")));
                        }
                    }
                } else throw new IllegalArgumentException("Unsupported verification instructions");
            }
            case "browser_verification_required" -> renderBrowser(verification);
            default -> { return; }
        }
        setVisibility(VISIBLE);
    }
    private static JsonObject presentation(JsonObject verification) {
        if (!verification.has("image")) return verification;
        if (!verification.get("image").isJsonObject()) throw new IllegalArgumentException("Invalid verification image");
        JsonObject image = verification.getAsJsonObject("image");
        for (String key : new String[]{"image_data_url", "refresh_after_secs", "answer_kind"})
            if (image.has(key)) verification.add(key, image.get(key).deepCopy());
        return verification;
    }
    private void renderBrowser(JsonObject verification) {
        label("browserVerification");
        button("openVerification", () -> submit.accept(action("open_browser")));
    }
    private EditText input(String key) {
        EditText field = new EditText(getContext(), null, icyllis.modernui.R.attr.editTextOutlinedStyle); field.setSingleLine();
        field.setHint(I18n.get(MusicHud.MOD_ID + ".field.hint." + key)); addView(field); return field;
    }
    private void label(String key) { text(I18n.get(MusicHud.MOD_ID + ".text.login." + key)); }
    private void text(String value) { TextView label = new TextView(getContext()); label.setTextColor(Theme.SECONDARY_TEXT_COLOR); label.setText(value); addView(label); }
    private void button(String key, Runnable click) {
        Button button = new Button(getContext()); button.setText(I18n.get(MusicHud.MOD_ID + ".button." + key));
        indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory.builder()
                .padding(new indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory.Padding(dp(8), dp(8), dp(8), dp(8)))
                .cornerRadius(dp(4)).build().applyBackgroundTo(button);
        button.setOnClickListener(v -> click.run()); addView(button);
    }
    static JsonObject action(String action) { JsonObject body = new JsonObject(); body.addProperty("action", action); return body; }
    private static String string(JsonObject value, String field) {
        var item = value.get(field); return item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() ? item.getAsString() : "";
    }
    private static long waitSeconds(JsonObject value, String field, int fallback) {
        var item = value.get(field);
        if (item == null || item.isJsonNull()) return fallback;
        try { return Math.clamp(item.getAsLong(), fallback, 300); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Invalid verification cooldown"); }
    }
}
