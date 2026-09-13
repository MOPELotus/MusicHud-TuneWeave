package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.state.IIdlePlaySourceCollectionState;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.interfaces.Unregister;
import net.minecraft.client.resources.language.I18n;

import java.util.concurrent.atomic.AtomicInteger;

public class ToggleIdlePlaySourceButton extends ToggleIconButton {
    private static final long TOGGLE_DEBOUNCE_DELAY_MILLIS = 800;
    private final indi.mopelotus.musichud.client.ui.BoundSourceToggle toggle = new indi.mopelotus.musichud.client.ui.BoundSourceToggle();
    private volatile IIdlePlaySourceCollectionState collectionState;
    private Unregister unregister = null;

    public ToggleIdlePlaySourceButton(Context context) {
        super(context, new Appearance(
                () -> I18n.get(MusicHud.MOD_ID + ".button.removeFromIdlePlaySource"),
                () -> I18n.get(MusicHud.MOD_ID + ".button.addToIdlePlaySource"),
                () -> ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/star_filled.png"),
                () -> ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/star.png")
        ));
    }

    @Override
    public boolean performClick() {
        boolean b = super.performClick();
        if (collectionState != null) {
            final boolean targetState = isChecked();
            Runnable apply = toggle.request(targetState);
            MusicHud.EXECUTOR.execute(() -> {
                try {
                    Thread.sleep(TOGGLE_DEBOUNCE_DELAY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                apply.run();
            });
        }
        return b;
    }

    public void bindMusicList(IIdlePlaySourceCollectionState collectionState) {
        this.collectionState = collectionState;
        long binding = toggle.bind(collectionState);
        if (collectionState == null) {
            this.collectionState = null;
            if (unregister != null) {
                unregister.unregister();
                unregister = null;
            }
        } else {
            MuiModApi.postToUiThread(() -> {
                if (!toggle.isBound(binding)) return;
                if (unregister != null) unregister.unregister();
                setChecked(collectionState.isContained());
                this.collectionState = collectionState;
                unregister = collectionState.onOthersModify(checked ->
                        MuiModApi.postToUiThread(() -> {
                            if (toggle.isBound(binding)) setChecked(checked);
                        }));
            });
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        toggle.bind(null);
        if (unregister != null) {
            unregister.unregister();
            unregister = null;
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (collectionState != null) bindMusicList(collectionState);
    }
}
