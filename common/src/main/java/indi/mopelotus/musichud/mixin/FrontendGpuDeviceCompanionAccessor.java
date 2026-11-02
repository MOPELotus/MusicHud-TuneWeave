package indi.mopelotus.musichud.mixin;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FrontendGpuDevice.class, remap = false)
public interface FrontendGpuDeviceCompanionAccessor {
    @Accessor("backend") GpuDeviceBackend musichud_tuneweave$backend();
}
