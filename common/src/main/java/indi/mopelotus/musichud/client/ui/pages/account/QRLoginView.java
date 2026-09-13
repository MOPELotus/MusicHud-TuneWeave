package indi.mopelotus.musichud.client.ui.pages.account;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.ArrayAdapter;
import icyllis.modernui.widget.Spinner;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.services.LoginService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveQrPoll;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveQrSession;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveLoginAttempt;
import indi.mopelotus.musichud.client.ui.CallbackGeneration;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.UrlImageView;
import indi.mopelotus.musichud.client.ui.components.PlatformSelector;
import indi.mopelotus.musichud.client.utils.image.QrImageUtils;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import net.minecraft.client.resources.language.I18n;

import java.time.Duration;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Direct client-mode QR login; the Minecraft server never sees the credential. */
public class QRLoginView extends LinearLayout implements ILoginView {
    private static final String[] QQ_LOGIN_TYPES = {"qq", "wechat", "mobile"};
    private final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();
    private final Button loginButton;
    private final PlatformSelector platformSelector;
    private final Spinner qqLoginTypeSpinner;
    private final UrlImageView qrImageView;
    private final TextView messageTextView;
    private volatile TuneWeaveQrSession activeSession;
    private MusicHud.ScheduledTask pollingTask;
    private TuneWeaveLoginAttempt attempt;
    private final CallbackGeneration callbacks = new CallbackGeneration();
    private long generation;

    public QRLoginView(Context context) {
        super(context);
        setOrientation(LinearLayout.VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(context);
        title.setTextSize(Theme.TEXT_SIZE_LARGE);
        title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        title.setText(I18n.get(MusicHud.MOD_ID + ".text.login.qrCode"));
        addView(title, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        TextView description = new TextView(context);
        description.setTextSize(Theme.TEXT_SIZE_NORMAL);
        description.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        description.setText(I18n.get(MusicHud.MOD_ID + ".text.login.description"));
        LayoutParams descriptionParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        descriptionParams.setMargins(0, dp(4), 0, 0);
        addView(description, descriptionParams);

        LinearLayout platformLayout = new LinearLayout(context);
        platformLayout.setOrientation(LinearLayout.HORIZONTAL);
        platformLayout.setGravity(Gravity.CENTER);
        platformSelector = new PlatformSelector(context, TuneWeavePlatform.values());
        platformSelector.setSelectedPlatform(tuneWeave.defaultPlatform());
        platformLayout.addView(platformSelector, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        qqLoginTypeSpinner = new Spinner(context);
        qqLoginTypeSpinner.setAdapter(new ArrayAdapter<>(context, new String[]{
                I18n.get(MusicHud.MOD_ID + ".login.qq"),
                I18n.get(MusicHud.MOD_ID + ".login.wechat"),
                I18n.get(MusicHud.MOD_ID + ".login.qqMusicClient")
        }));
        LayoutParams qqParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        qqParams.setMargins(dp(8), 0, 0, 0);
        platformLayout.addView(qqLoginTypeSpinner, qqParams);
        addView(platformLayout, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        qrImageView = new UrlImageView(context);
        LayoutParams imageParams = new LayoutParams(dp(240), dp(240));
        imageParams.setMargins(0, dp(20), 0, 0);
        addView(qrImageView, imageParams);

        loginButton = new Button(context);
        loginButton.setTextColor(Theme.PRIMARY_COLOR);
        loginButton.setHeight(dp(36));
        loginButton.setWidth(dp(112));
        loginButton.setTextSize(Theme.TEXT_SIZE_NORMAL);
        loginButton.setText(I18n.get(MusicHud.MOD_ID + ".button.loadQRCode"));
        loginButton.setBackground(ButtonInsetBackgroundFactory.builder()
                .padding(new ButtonInsetBackgroundFactory.Padding(0, 0, 0, 0))
                .cornerRadius(dp(4)).inset(dp(1)).build().newBackgroundDrawable());
        LayoutParams buttonParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        buttonParams.setMargins(0, dp(8), 0, 0);
        addView(loginButton, buttonParams);

        messageTextView = new TextView(context);
        messageTextView.setTextSize(Theme.TEXT_SIZE_NORMAL);
        messageTextView.setMaxWidth(dp(400));
        messageTextView.setMinHeight(36);
        messageTextView.setSingleLine(false);
        messageTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        messageTextView.setVisibility(GONE);
        LayoutParams messageParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        messageParams.setMargins(0, dp(8), 0, 0);
        addView(messageTextView, messageParams);

        loginButton.setOnClickListener(view -> startLogin());
        platformSelector.setOnPlatformSelectedListener(platform ->
                qqLoginTypeSpinner.setVisibility(platform == TuneWeavePlatform.QQ ? VISIBLE : GONE));
        qqLoginTypeSpinner.setVisibility(platformSelector.getSelectedPlatform() == TuneWeavePlatform.QQ ? VISIBLE : GONE);
        qrImageView.setLoading(false);

        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                setBusy(false);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                stopPolling();
            }
        });
    }

    private void startLogin() {
        stopPolling();
        setBusy(true);
        qrImageView.clear();
        messageTextView.setVisibility(GONE);
        TuneWeavePlatform platform = selectedPlatform();
        TuneWeaveLoginAttempt token = attempt = tuneWeave.beginLogin(platform);
        long ticket = generation;
        String loginType = platform == TuneWeavePlatform.QQ
                ? QQ_LOGIN_TYPES[Math.clamp(qqLoginTypeSpinner.getSelectedItemPosition(), 0,
                        QQ_LOGIN_TYPES.length - 1)]
                : null;
        MusicHud.EXECUTOR.execute(() -> {
            try {
                TuneWeaveQrSession session = tuneWeave.startQrLogin(token, loginType);
                String image = QrImageUtils.prepare(session.imageDataUrl(), session.url());
                callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
                    if (!tuneWeave.isLoginCurrent(token)) return;
                    activeSession = session;
                    qrImageView.loadUrl(image);
                    messageTextView.setText(I18n.get(MusicHud.MOD_ID + ".text.login.waitingForScan"));
                    messageTextView.setTextColor(Theme.SECONDARY_TEXT_COLOR);
                    messageTextView.setVisibility(VISIBLE);
                    var inFlight = new java.util.concurrent.atomic.AtomicBoolean();
                    pollingTask = MusicHud.scheduleWithFixedDelay(() -> {
                        if (!callbacks.isCurrent(ticket) || !inFlight.compareAndSet(false, true)) return;
                        try { pollLogin(session, token, ticket); } finally { inFlight.set(false); }
                    }, Duration.ofSeconds(2), Duration.ofSeconds(2));
                });
            } catch (RuntimeException error) {
                failLogin(ticket, error);
            }
        });
    }

    private void pollLogin(TuneWeaveQrSession session, TuneWeaveLoginAttempt token, long ticket) {
        try {
            TuneWeaveQrPoll poll = tuneWeave.pollQrLogin(session);
            String localizedMessage = localizedPollMessage(poll.state(), poll.message());
            callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
                if (!tuneWeave.isLoginCurrent(token)) return;
                if ("scanned".equals(poll.state())) {
                    messageTextView.setText(I18n.get(MusicHud.MOD_ID + ".text.login.scanned"));
                } else if (!localizedMessage.isBlank()) {
                    messageTextView.setText(localizedMessage);
                }
                if (poll.terminal()) {
                if ("confirmed".equals(poll.state())) {
                    try {
                        LoginService.getInstance().completeTuneWeaveLogin(token, poll.profile());
                    } catch (java.util.concurrent.CancellationException ignored) { }
                    stopPolling();
                } else {
                    stopPolling();
                    showError(localizedMessage);
                    setBusy(false);
                }
                }
            });
        } catch (RuntimeException error) {
            failLogin(ticket, error);
        }
    }

    private void failLogin(long ticket, RuntimeException error) {
        callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
            stopPolling();
            if (!(error instanceof java.util.concurrent.CancellationException))
                showError(I18n.get(MusicHud.MOD_ID + ".text.login.failed"));
            setBusy(false);
        });
    }

    private TuneWeavePlatform selectedPlatform() {
        return platformSelector.getSelectedPlatform();
    }

    private static String localizedPollMessage(String state, String fallback) {
        return switch (state == null ? "" : state) {
            case "waiting" -> I18n.get(MusicHud.MOD_ID + ".text.login.waitingForScan");
            case "scanned" -> I18n.get(MusicHud.MOD_ID + ".text.login.scanned");
            case "expired" -> I18n.get(MusicHud.MOD_ID + ".text.login.qrExpired");
            case "failed" -> I18n.get(MusicHud.MOD_ID + ".text.login.failed");
            default -> fallback == null ? "" : fallback;
        };
    }

    private void setBusy(boolean busy) {
        callbacks.post(MuiModApi::postToUiThread, generation, () -> {
            loginButton.setClickable(!busy);
            loginButton.setAlpha(busy ? 0.55f : 1f);
            platformSelector.setEnabled(!busy);
            qqLoginTypeSpinner.setClickable(!busy);
            if (busy) {
                qrImageView.setLoading(true);
            }
        });
    }

    private void stopPolling() {
        generation = callbacks.next();
        tuneWeave.cancelLogin(attempt);
        attempt = null;
        MusicHud.ScheduledTask task = pollingTask;
        pollingTask = null;
        activeSession = null;
        if (task != null) {
            task.stop();
        }
    }

    private void showError(String message) {
        callbacks.post(MuiModApi::postToUiThread, generation, () -> {
            messageTextView.setTextColor(Theme.ERROR_TEXT_COLOR);
            messageTextView.setText(message == null || message.isBlank()
                    ? I18n.get(MusicHud.MOD_ID + ".button.loadingError") : message);
            messageTextView.setVisibility(VISIBLE);
        });
    }

    @Override
    public void reset() {
        stopPolling();
        loginButton.setVisibility(VISIBLE);
        setBusy(false);
        qrImageView.clear();
        messageTextView.setVisibility(GONE);
    }

    @Override
    public void errorText(String message) {
        showError(message);
        setBusy(false);
    }
}
