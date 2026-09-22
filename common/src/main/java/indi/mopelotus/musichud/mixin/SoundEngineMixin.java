package indi.mopelotus.musichud.mixin;

import indi.mopelotus.musichud.client.audio.StreamAudioPlayer;
import indi.mopelotus.musichud.client.audio.SoundEngineEpoch;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public class SoundEngineMixin {
    @Inject(method = {"reload", "destroy", "emergencyShutdown"}, at = @At("HEAD"))
    private void musichud_tuneweave$invalidateDevice(CallbackInfo ci) {
        SoundEngineEpoch.invalidate();
    }

    @Inject(method = "loadLibrary", at = @At("RETURN"))
    private void musichud_tuneweave$deviceReady(CallbackInfo ci) {
        SoundEngineEpoch.ready();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void musichud_tuneweave$clearExternalEffects(CallbackInfo ci) {
        indi.mopelotus.musichud.client.audio.OpenAlEffects.clearExternalEffects();
    }

    @Unique
    private static final ClientConfig musichud_tuneweave$clientConfig = ClientConfig.getInstance();

    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",
            at = @At("HEAD"), cancellable = true)
    private void onPlaySound(SoundInstance soundInstance, CallbackInfo cir) {
        if (musichud_tuneweave$clientConfig.isConfigured()
                && soundInstance.getSource() == SoundSource.MUSIC
                && musichud_tuneweave$clientConfig.getDisableVanillaMusic()
                && StreamAudioPlayer.getInstance().getStatus() == StreamAudioPlayer.Status.PLAYING) {
            cir.cancel();

        }
    }
}
