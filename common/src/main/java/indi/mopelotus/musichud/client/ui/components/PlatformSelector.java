package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.drawable.InsetDrawable;
import icyllis.modernui.util.ColorStateList;
import icyllis.modernui.util.StateSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.widget.CheckableImageButton;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ImageView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.utils.image.PlatformIconUtils;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import net.minecraft.client.resources.language.I18n;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Compact branded platform switcher used wherever a TuneWeave route is selected. */
public final class PlatformSelector extends LinearLayout {
    private static final ColorStateList SEGMENT_STATES = new ColorStateList(
            new int[][]{
                    new int[]{R.attr.state_checked},
                    new int[]{R.attr.state_pressed},
                    new int[]{R.attr.state_hovered},
                    StateSet.WILD_CARD
            },
            new int[]{
                    0x35E0BFB7,
                    0x18FFFFFF,
                    0x10FFFFFF,
                    0x00000000
            }
    );

    private final Map<TuneWeavePlatform, CheckableImageButton> buttons = new EnumMap<>(TuneWeavePlatform.class);
    private CheckableImageButton auxiliaryButton;
    private TuneWeavePlatform selected;
    private Consumer<TuneWeavePlatform> listener;

    public PlatformSelector(Context context, TuneWeavePlatform... platforms) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding(dp(2), dp(2), dp(2), dp(2));
        setBackground(ButtonInsetBackgroundFactory.builder()
                .backgroundColor(Theme.GHOST_BUTTON_STATES)
                .cornerRadius(dp(6)).inset(dp(1)).build().newBackgroundDrawable());
        for (TuneWeavePlatform platform : platforms) {
            CheckableImageButton button = new CheckableImageButton(context);
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            Image image = PlatformIconUtils.image(platform, dp(20));
            if (image != null) {
                button.setImageDrawable(new InsetDrawable(
                        new indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable(
                                context.getResources(), image, dp(20), dp(20)), dp(3)));
            }
            button.setTooltipText(I18n.get(MusicHud.MOD_ID + ".platform." + platform.apiName()));
            button.setBackground(ButtonInsetBackgroundFactory.builder()
                    .backgroundColor(SEGMENT_STATES)
                    .padding(new ButtonInsetBackgroundFactory.Padding(dp(4), dp(4), dp(4), dp(4)))
                    .cornerRadius(dp(4)).inset(0).build().newBackgroundDrawable());
            button.setOnClickListener(view -> select(platform, true));
            button.setContentDescription(I18n.get(MusicHud.MOD_ID + ".platform." + platform.apiName()));
            buttons.put(platform, button);
            LayoutParams params = new LayoutParams(dp(38), dp(30));
            params.setMargins(dp(1), 0, dp(1), 0);
            addView(button, params);
        }
        if (platforms.length > 0) {
            select(platforms[0], false);
        }
    }

    public TuneWeavePlatform getSelectedPlatform() {
        return selected;
    }

    public void setSelectedPlatform(TuneWeavePlatform platform) {
        if (platform != null && buttons.containsKey(platform)) {
            select(platform, false);
        }
    }

    public void setOnPlatformSelectedListener(Consumer<TuneWeavePlatform> listener) {
        this.listener = listener;
    }

    public void addAuxiliarySegment(Image image, CharSequence label, boolean checked, Runnable listener) {
        if (auxiliaryButton != null) removeView(auxiliaryButton);
        CheckableImageButton button = new CheckableImageButton(getContext());
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (image != null) {
            button.setImageDrawable(new InsetDrawable(
                    new indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable(
                            getContext().getResources(), image, dp(20), dp(20)), dp(3)));
        }
        button.setTooltipText(label);
        button.setContentDescription(label);
        button.setBackground(ButtonInsetBackgroundFactory.builder()
                .backgroundColor(SEGMENT_STATES)
                .padding(new ButtonInsetBackgroundFactory.Padding(dp(4), dp(4), dp(4), dp(4)))
                .cornerRadius(dp(4)).inset(0).build().newBackgroundDrawable());
        button.setOnClickListener(view -> {
            selected = null;
            buttons.values().forEach(platformButton -> platformButton.setChecked(false));
            button.setChecked(true);
            if (listener != null) listener.run();
        });
        auxiliaryButton = button;
        LayoutParams params = new LayoutParams(dp(38), dp(30));
        params.setMargins(dp(1), 0, dp(1), 0);
        addView(button, params);
        if (checked) {
            selected = null;
            buttons.values().forEach(platformButton -> platformButton.setChecked(false));
            button.setChecked(true);
        }
    }

    private void select(TuneWeavePlatform platform, boolean notify) {
        if (!buttons.containsKey(platform)) return;
        selected = Objects.requireNonNull(platform);
        buttons.forEach((key, button) -> button.setChecked(key == platform));
        if (auxiliaryButton != null) auxiliaryButton.setChecked(false);
        if (notify && listener != null) listener.accept(platform);
    }
}
