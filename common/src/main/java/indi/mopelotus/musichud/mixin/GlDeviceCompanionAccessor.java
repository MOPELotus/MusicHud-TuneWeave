package indi.mopelotus.musichud.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlDevice", remap = false)
public interface GlDeviceCompanionAccessor {
    @Accessor("currentWindow") long musichud_tuneweave$currentWindow();
    @Accessor("currentWindow") void musichud_tuneweave$currentWindow(long window);
    @Accessor("glContext") long musichud_tuneweave$context();
    @Accessor("initialWindowHandle") long musichud_tuneweave$utilityWindow();
}
