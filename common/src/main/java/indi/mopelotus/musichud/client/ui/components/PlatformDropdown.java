package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.ArrayAdapter;
import icyllis.modernui.widget.ImageView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.Spinner;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable;
import indi.mopelotus.musichud.client.utils.image.PlatformIconUtils;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import net.minecraft.client.resources.language.I18n;
import java.util.function.Consumer;

/** Uses the same Spinner/ArrayAdapter control as the upstream settings UI. */
public final class PlatformDropdown extends Spinner {
    private final TuneWeavePlatform[] platforms = PlatformDisplayOrder.platforms().toArray(TuneWeavePlatform[]::new);
    private TuneWeavePlatform selected = platforms[0];
    private Consumer<TuneWeavePlatform> listener;

    public PlatformDropdown(Context context) {
        super(context);
        setAdapter(new ArrayAdapter<TuneWeavePlatform>(context, platforms) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                return row(position, recycled);
            }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                return row(position, recycled);
            }
            private View row(int position, View recycled) {
                LinearLayout row;
                if (recycled instanceof LinearLayout existing) row = existing;
                else {
                    row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(12), dp(8), dp(12), dp(8));
                    row.addView(new ImageView(context), new LinearLayout.LayoutParams(dp(20), dp(20)));
                    TextView label = new TextView(context);
                    label.setTextSize(Theme.TEXT_SIZE_NORMAL);
                    label.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
                    label.setSingleLine(true);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                    params.setMargins(dp(8), 0, 0, 0);
                    row.addView(label, params);
                }
                var platform = platforms[position];
                var image = PlatformIconUtils.image(platform, dp(20));
                ((ImageView) row.getChildAt(0)).setImageDrawable(image == null ? null
                        : new ScaledImageDrawable(context.getResources(), image, dp(20), dp(20)));
                ((TextView) row.getChildAt(1)).setText(I18n.get(MusicHud.MOD_ID + ".platform." + platform.apiName()));
                return row;
            }
        });
        setOnItemSelectedListener((parent, view, position, id) -> {
            var platform = platforms[position];
            if (selected == platform) return;
            selected = platform;
            if (listener != null) listener.accept(platform);
        });
    }

    public TuneWeavePlatform getSelectedPlatform() { return selected; }
    public void setSelectedPlatform(TuneWeavePlatform platform) {
        if (platform == null) return;
        selected = platform;
        setSelection(platform.ordinal());
    }
    public void setOnPlatformSelectedListener(Consumer<TuneWeavePlatform> listener) { this.listener = listener; }
}
