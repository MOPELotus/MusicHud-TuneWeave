package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.animation.Animator;
import icyllis.modernui.animation.AnimatorListener;
import icyllis.modernui.animation.ObjectAnimator;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.util.IntProperty;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.LinearLayout;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.state.IIdlePlaySourceCollectionState;
import indi.mopelotus.musichud.beans.state.IIdlePlaySourceLayerState;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.CycleIconButton;
import indi.mopelotus.musichud.client.ui.components.ToggleIdlePlaySourceButton;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ui.Easing;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import indi.mopelotus.musichud.interfaces.Unregister;
import indi.mopelotus.musichud.beans.api.IdlePlayMode;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class IdlePlaySourceWidget extends LinearLayout {
    private final IIdlePlaySourceLayerState layer = MusicService.getInstance().getIdlePlaySourceState().local();
    private final IIdlePlaySourceCollectionState modeState;
    private long lifecycleGeneration;
    private boolean changingMode;
    private final MusicCollection collection;
    private final ToggleIdlePlaySourceButton toggleButton;
    private final CycleIconButton cycleButton;
    private final List<IdlePlayMode> cycleModes = new ArrayList<>();
    private final int cycleTargetSize;
    private final int cycleCollapseSize = 0;
    private int cycleCurrentWidth = 0;
    private Animator cycleShowAnimator;
    private Unregister addRegister;
    private Unregister removeRegister;
    private Unregister changeRegister;
    private Unregister errorRegister;

    /** Animates the cycle button's LayoutParams width; the row reflows each frame. */
    private static final IntProperty<IdlePlaySourceWidget> CYCLE_WIDTH = new IntProperty<>("cycleWidth") {
        @Override
        public void setValue(IdlePlaySourceWidget widget, int width) {
            widget.setCycleWidth(width);
        }

        @Override
        public Integer get(IdlePlaySourceWidget widget) {
            return widget.cycleCurrentWidth;
        }
    };

    public IdlePlaySourceWidget(Context context, MusicCollection collection, int buttonSize) {
        super(context);
        this.collection = collection;
        this.modeState = layer.collection(collection);
        this.cycleTargetSize = buttonSize;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        var backgroundFactory = InsetBackgroundFactory.builder()
                .backgroundColor(Theme.GHOST_BUTTON_STATES)
                .inset(0)
                .cornerRadius(dp(4))
                .padding(new InsetBackgroundFactory.Padding(dp(2), dp(2), dp(2), dp(2)))
                .build();

        toggleButton = new ToggleIdlePlaySourceButton(context);
        backgroundFactory.applyBackgroundTo(toggleButton);
        addView(toggleButton, new LayoutParams(buttonSize, buttonSize));

        // Shown/hidden purely by animated width+alpha (no GONE/VISIBLE flips, no
        // LayoutTransition): starts collapsed and fully transparent, which is visually
        // identical to GONE but keeps the animation fully under our control
        cycleButton = new CycleIconButton(context);
        backgroundFactory.applyBackgroundTo(cycleButton);
        addView(cycleButton, new LayoutParams(cycleCollapseSize, buttonSize));
        cycleButton.setAlpha(0f);

        buildCycleStates();
        bindToggle();
    }

    private void buildCycleStates() {
        cycleModes.clear();
        cycleButton.getStates().clear();
        addCycleState(IdlePlayMode.RANDOM, "/assets/musichud_tuneweave/textures/gui/icons/shuffle.png");
        addCycleState(IdlePlayMode.SEQUENTIAL, "/assets/musichud_tuneweave/textures/gui/icons/repeat.png");
    }

    private void addCycleState(IdlePlayMode playMode, String iconPath) {
        cycleModes.add(playMode);
        cycleButton.getStates().add(new CycleIconButton.State(
                () -> I18n.get(MusicHud.MOD_ID + ".button.idlePlaySourceMode." + playMode.name()),
                () -> ImageUtils.getImageFromResource(iconPath),
                () -> changeMode(playMode),
                () -> {
                }
        ));
    }

    private void bindToggle() {
        toggleButton.bindMusicList(modeState);
    }

    private void syncCycleState() {
        cycleButton.apply(Math.max(0, cycleModes.indexOf(layer.getPlayMode(collection))));
        cycleButton.setEnabled(!changingMode);
        boolean failed = layer.isInLoadError(collection.getClass(), collection.getId());
        cycleButton.setWarned(failed, I18n.get(MusicHud.MOD_ID + ".text.idleSourceLoadFailed")
                        + " · " + I18n.get(MusicHud.MOD_ID + ".button.retry"),
                failed ? ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/triangle_alert.png") : null,
                this::recover);
        setCycleShown(modeState.isContained() || failed);
    }

    private void recover() {
        if (changingMode || !isAttachedToWindow()) return;
        changingMode = true;
        cycleButton.setEnabled(false);
        long expected = lifecycleGeneration;
        layer.recover(collection.getClass(), collection.getId()).whenComplete((success, error) ->
                MuiModApi.postToUiThread(() -> {
                    if (expected != lifecycleGeneration || !isAttachedToWindow()) return;
                    changingMode = false;
                    syncCycleState();
                    if (!Boolean.TRUE.equals(success)) indi.mopelotus.musichud.client.ui.ToastUtil.show(
                            icyllis.modernui.widget.Toast.makeText(getContext(),
                                    I18n.get(MusicHud.MOD_ID + ".text.idleSourceRecoveryFailed"), icyllis.modernui.widget.Toast.LENGTH_SHORT));
                }));
    }

    private void changeMode(IdlePlayMode mode) {
        if (changingMode || !isAttachedToWindow()) return;
        changingMode = true;
        cycleButton.setEnabled(false);
        long expected = lifecycleGeneration;
        Object scope = indi.mopelotus.musichud.client.services.music.MusicEntityCache.captureGeneration();
        java.util.concurrent.CompletableFuture.runAsync(() ->
                indi.mopelotus.musichud.client.services.music.MusicEntityCache.publish(scope,
                        () -> layer.setPlayMode(collection, mode)), MusicHud.EXECUTOR)
                .whenComplete((ignored, error) -> MuiModApi.postToUiThread(() -> {
                    if (expected != lifecycleGeneration || !isAttachedToWindow()) return;
                    changingMode = false;
                    syncCycleState();
                    if (error != null) indi.mopelotus.musichud.client.ui.ToastUtil.show(
                            icyllis.modernui.widget.Toast.makeText(getContext(),
                                    I18n.get(MusicHud.MOD_ID + ".button.loadingError"), icyllis.modernui.widget.Toast.LENGTH_SHORT));
                }));
    }

    /** Two-phase show/hide, fully self-driven. Show: width 0→target (150ms, QUAD) then
     *  alpha 0→1 (100ms, SINE). Hide: alpha 1→0 (100ms, SINE) then width target→0
     *  (150ms, QUAD). Restarting from the current width/alpha keeps rapid toggles smooth;
     *  a canceled phase never chains into the next one. */
    private void setCycleShown(boolean shown) {
        cancelCycleShowAnimator();
        ObjectAnimator first;
        ObjectAnimator second;
        if (shown) {
            if (cycleCurrentWidth == cycleTargetSize && cycleButton.getAlpha() == 1f) {
                return;
            }
            first = ObjectAnimator.ofInt(this, CYCLE_WIDTH, cycleCurrentWidth, cycleTargetSize);
            first.setDuration(150);
            first.setInterpolator(Easing.EASE_IN_OUT_CUBIC);
            second = ObjectAnimator.ofFloat(cycleButton, View.ALPHA, cycleButton.getAlpha(), 1f);
            second.setDuration(100);
            second.setInterpolator(Easing.EASE_IN_OUT_SINE);
        } else {
            if (cycleCurrentWidth == cycleCollapseSize && cycleButton.getAlpha() == 0f) {
                return;
            }
            first = ObjectAnimator.ofFloat(cycleButton, View.ALPHA, cycleButton.getAlpha(), 0f);
            first.setDuration(100);
            first.setInterpolator(Easing.EASE_IN_OUT_SINE);
            second = ObjectAnimator.ofInt(this, CYCLE_WIDTH, cycleCurrentWidth, cycleCollapseSize);
            second.setDuration(150);
            second.setInterpolator(Easing.EASE_IN_OUT_CUBIC);
        }
        second.addListener(new AnimatorListener() {
            @Override
            public void onAnimationEnd(@NonNull Animator animation) {
                if (cycleShowAnimator == animation) {
                    cycleShowAnimator = null;
                }
            }
        });
        first.addListener(new AnimatorListener() {
            @Override
            public void onAnimationEnd(@NonNull Animator animation) {
                // A canceled phase must not chain into the next one: cancelCycleShowAnimator
                // nulls the field before canceling, so a stale end callback is ignored
                if (cycleShowAnimator != animation) {
                    return;
                }
                cycleShowAnimator = second;
                second.start();
            }
        });
        cycleShowAnimator = first;
        first.start();
    }

    private void cancelCycleShowAnimator() {
        if (cycleShowAnimator != null) {
            Animator active = cycleShowAnimator;
            cycleShowAnimator = null;
            active.cancel();
        }
    }

    private void setCycleWidth(int width) {
        cycleCurrentWidth = width;
        LayoutParams lp = (LayoutParams) cycleButton.getLayoutParams();
        if (lp.width != width) {
            lp.width = width;
            cycleButton.setLayoutParams(lp);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ++lifecycleGeneration;
        changingMode = false;
        Consumer<MusicCollection> changed = value -> {
            if (value.getId() == collection.getId() && value.getClass() == collection.getClass()) postSync();
        };
        addRegister = layer.onAdd(changed);
        removeRegister = layer.onRemove(changed);
        changeRegister = layer.onChange(changed);
        errorRegister = layer.onLoadErrorChanged(source -> {
            if (source.getId() == collection.getId() && source.getType() == collection.getClass()) postSync();
        });
        syncCycleState();
    }

    @Override
    protected void onDetachedFromWindow() {
        ++lifecycleGeneration;
        cancelCycleShowAnimator();
        if (addRegister != null) { addRegister.unregister(); addRegister = null; }
        if (removeRegister != null) { removeRegister.unregister(); removeRegister = null; }
        if (errorRegister != null) { errorRegister.unregister(); errorRegister = null; }
        if (changeRegister != null) { changeRegister.unregister(); changeRegister = null; }
        super.onDetachedFromWindow();
    }

    private void postSync() {
        long expected = lifecycleGeneration;
        MuiModApi.postToUiThread(() -> {
            if (expected == lifecycleGeneration && isAttachedToWindow()) syncCycleState();
        });
    }
}
