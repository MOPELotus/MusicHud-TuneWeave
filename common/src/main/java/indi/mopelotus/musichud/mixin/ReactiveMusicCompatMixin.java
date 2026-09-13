package indi.mopelotus.musichud.mixin;

import indi.mopelotus.musichud.client.audio.StreamAudioPlayer;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "circuitlord.reactivemusic.ReactiveMusic", remap = false)
public abstract class ReactiveMusicCompatMixin {
    @Unique
    private static StreamAudioPlayer musichud_tuneweave$streamAudioPlayer;
    @Unique
    private static ClientConfig musichud_tuneweave$clientConfig;
    @Unique
    private static boolean musichud_tuneweave$reactive_music_killed = false;

    @SuppressWarnings("UnresolvedMixinReference")
    @Inject(method = "newTick", at = @At("HEAD"), cancellable = true)
    private static void onNewTick(CallbackInfo ci) {
        if (musichud_tuneweave$streamAudioPlayer == null) {
            musichud_tuneweave$streamAudioPlayer = StreamAudioPlayer.getInstance();
        }
        if (musichud_tuneweave$clientConfig == null) {
            musichud_tuneweave$clientConfig = ClientConfig.getInstance();
        }
        boolean shouldMute = musichud_tuneweave$streamAudioPlayer.getStatus() == StreamAudioPlayer.Status.PLAYING
                && musichud_tuneweave$clientConfig.getDisableVanillaMusic();
        if (!shouldMute) {
            musichud_tuneweave$reactive_music_killed = false;
            return;
        }

        if (!musichud_tuneweave$reactive_music_killed) {
            musichud_tuneweave$reactive_music_killed = true;
            try {
                Object thread = Class.forName("circuitlord.reactivemusic.ReactiveMusic")
                        .getField("thread").get(null);
                thread.getClass().getMethod("resetPlayer").invoke(thread);
            } catch (Exception ignored) {}
        }
        ci.cancel();
    }
}
