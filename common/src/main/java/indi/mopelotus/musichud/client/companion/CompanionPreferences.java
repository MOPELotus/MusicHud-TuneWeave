package indi.mopelotus.musichud.client.companion;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.ui.PreferencesFragment;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/** Uses the same preference controls as the rest of MusicHud's settings menu. */
public final class CompanionPreferences extends LinearLayout {
    private final LiveBoolean enabled;
    private final LiveBoolean locked;
    private final TextView status;
    private boolean enableFailed;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            enabled.sync(true);
            locked.sync(CompanionBridge.connected());
            status.setText(tr(enableFailed ? "unavailable" : CompanionBridge.connected() ? "connected" : "waiting"));
            postDelayed(this, 200);
        }
    };

    public CompanionPreferences(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        var category = PreferencesFragment.createCategoryList(this, tr("title"));
        enabled = new LiveBoolean(context, tr("enabled"), CompanionBridge::enabled, value ->
                Minecraft.getInstance().execute(() -> {
                    CompanionBridge.setEnabled(value);
                    post(() -> enableFailed = value && !CompanionBridge.enabled());
                }));
        enabled.create(category);
        locked = new LiveBoolean(context, tr("locked"), CompanionBridge::locked, CompanionBridge::requestLocked);
        locked.create(category);
        Integer[] rates = {30, 60};
        new PreferencesFragment.DropDownOption<>(context, tr("frameLimit"), rates,
                value -> value == 30 ? 0 : 1, CompanionBridge::fps, value -> Minecraft.getInstance().execute(() -> CompanionBridge.setFps(value))).create(category);
        Integer[] scales = {1, 2, 3, 4};
        new PreferencesFragment.DropDownOption<>(context, tr("scale"), scales,
                value -> value - 1, CompanionBridge::scale, value -> Minecraft.getInstance().execute(() -> CompanionBridge.setScale(value))).create(category);
        status = new TextView(context);
        status.setTextSize(14);
        var statusRow = new LinearLayout(context);
        statusRow.setGravity(icyllis.modernui.view.Gravity.CENTER_VERTICAL);
        var statusParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44));
        statusParams.setMargins(dp(6), 0, dp(6), 0);
        statusRow.addView(status, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1));
        category.addView(statusRow, statusParams);
        CompanionManagementUi.create(category, statusRow);
        addView(category, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private static String tr(String key) { return I18n.get("musichud_tuneweave.companion." + key); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(refresh);
        post(refresh);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(refresh);
        super.onDetachedFromWindow();
    }

    private static final class LiveBoolean extends PreferencesFragment.BooleanOption {
        LiveBoolean(Context context, String label, java.util.function.Supplier<Boolean> getter,
                    java.util.function.Consumer<Boolean> setter) { super(context, label, getter, setter); }
        void sync(boolean interactive) {
            // Reconnection locks the real window. Reflect it without sending an unlock back.
            button.setOnCheckedChangeListener(null);
            button.setChecked(getter.get());
            button.setOnCheckedChangeListener(this);
            button.setEnabled(interactive);
            layout.setEnabled(interactive);
        }
    }
}
