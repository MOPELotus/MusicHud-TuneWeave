package indi.mopelotus.musichud.client.ui.hud.pipelines;

import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

public interface HudUniform extends DynamicGpuDataStorage.DynamicGpuData {
    String getUBOName();
    int getUBOSize();
    void write(Std140Builder builder);
    boolean shouldUseBuffer(HudUniform lastBuffered);
    default void write(@NotNull ByteBuffer byteBuffer) {
        write(Std140Builder.intoBuffer(byteBuffer));
    }
}