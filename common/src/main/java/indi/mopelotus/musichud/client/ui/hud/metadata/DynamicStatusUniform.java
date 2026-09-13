package indi.mopelotus.musichud.client.ui.hud.metadata;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.audio.NowPlayingInfo;
import indi.mopelotus.musichud.client.ui.hud.pipelines.HudUniform;
import indi.mopelotus.musichud.client.utils.ui.Transitionable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@EqualsAndHashCode
public class DynamicStatusUniform implements HudUniform {
    private static volatile DynamicStatusUniform instance;
    @Getter
    @Setter
    Transitionable<?> transitionable;
    public static final int UBO_SIZE = new Std140SizeCalculator().putVec4().align(16).get();
    private static final NowPlayingInfo NOW_PLAYING_INFO = NowPlayingInfo.getInstance();

    public static DynamicStatusUniform getInstance() {
        if (instance == null) {
            synchronized (DynamicStatusUniform.class) {
                if (instance == null) {
                    instance = new DynamicStatusUniform();
                }
            }
        }
        return instance;
    }
    private DynamicStatusUniform() {}

    @Override
    public String getUBOName() {
        return "MHDynamicStatus";
    }

    @Override
    public int getUBOSize() {
        return UBO_SIZE;
    }

    @Override
    public void write(Std140Builder builder) {
        builder.putVec4(
                (float) MusicHud.getRunningMillis() / 1000,
                NOW_PLAYING_INFO.getProgressRate(),
                transitionable == null ? 0 : transitionable.getProgress(),
                0
        );
    }

    @Override
    public boolean shouldUseBuffer(HudUniform lastBuffered) {
        return false;//always update
    }
}
