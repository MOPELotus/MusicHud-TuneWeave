package indi.mopelotus.musichud.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.Listener;
import indi.mopelotus.musichud.client.audio.DynamicFpsMasterGain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.Map;

/** Legacy Dynamic FPS versions duck the listener shared with our independent sources. */
@Mixin(value = SoundEngine.class, priority = 1100)
public abstract class SoundEngineDynamicFpsCompatMixin {
    @Shadow @Final private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;
    @Shadow private float calculateVolume(SoundInstance sound) { throw new AssertionError(); }
    @Unique private final DynamicFpsMasterGain musichud_tuneweave$dynamicFpsGain = new DynamicFpsMasterGain();

    @WrapOperation(method = "dynamic_fps$updateVolume", remap = false, require = 0, expect = 0,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/Listener;setGain(F)V", remap = true))
    private void musichud_tuneweave$isolateMasterDucking(Listener listener, float duckedGain, Operation<Void> original) {
        float configured = Minecraft.getInstance().options.getSoundSourceOptionInstance(SoundSource.MASTER).get().floatValue();
        original.call(listener, musichud_tuneweave$dynamicFpsGain.listenerGain(configured, duckedGain));
        // Only vanilla-managed channels receive the Dynamic FPS master multiplier.
        // Setting gain directly preserves Dynamic FPS's own music pause/resume decisions.
        for (SoundInstance sound : new ArrayList<>(instanceToChannel.keySet())) {
            ChannelAccess.ChannelHandle handle = instanceToChannel.get(sound);
            if (handle != null) {
                float gain = calculateVolume(sound);
                handle.execute(channel -> channel.setVolume(gain));
            }
        }
    }

    @ModifyReturnValue(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"))
    private float musichud_tuneweave$duckVanillaSources(float gain) {
        return musichud_tuneweave$dynamicFpsGain.vanillaSourceGain(gain);
    }
}
