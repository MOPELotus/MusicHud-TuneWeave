package indi.mopelotus.musichud.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import indi.mopelotus.musichud.client.audio.OwnedAudioSources;
import net.minecraft.client.sounds.SoundEngine;
import org.lwjgl.openal.ALC10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Optional SPP hook adapted from MusicHud's bff0f67b compatibility change. */
@Mixin(value = SoundEngine.class, priority = 9100)
public abstract class SoundEngineSppCompatMixin {
    @SuppressWarnings({"UnresolvedMixinReference", "MixinAnnotationTarget"})
    @WrapOperation(method = "updateActiveSources", remap = false, require = 0, expect = 0,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL10;alIsSource(I)Z", remap = false))
    private static boolean musichud_tuneweave$skipOwnedSources(int source, Operation<Boolean> original) {
        boolean active = original.call(source);
        return active && !OwnedAudioSources.INSTANCE.owns(ALC10.alcGetCurrentContext(), source);
    }
}
