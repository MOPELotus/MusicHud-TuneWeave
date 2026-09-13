package indi.mopelotus.musichud.network.payloads.pushMessages.s2c;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.PlaybackResolution;
import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.IClientMusicService;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.network.IClientNetworkService;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.ResolvePlaybackResultMessage;
import indi.mopelotus.musichud.platform.Environment;

import java.util.UUID;

/** A narrowly scoped request for resolving the current or next public playback only. */
public record ResolvePlaybackRequestMessage(UUID requestId, int revision,
                                            MusicDetail requestedMusic) implements S2CPayload {
    public static final ByteBufCodec<ResolvePlaybackRequestMessage> CODEC = ByteBufCodec.composite(
            Codecs.UUID, ResolvePlaybackRequestMessage::requestId,
            Codecs.INT, ResolvePlaybackRequestMessage::revision,
            MusicDetail.CODEC, ResolvePlaybackRequestMessage::requestedMusic,
            ResolvePlaybackRequestMessage::new
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        @Override
        public void register() {
            NetworkReceiver<ResolvePlaybackRequestMessage> receiver = NetworkReceiver.noop();
            if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
                receiver = (message, player) -> {
                    var admission = indi.mopelotus.musichud.network.ClientPacketContext.capture();
                    if (!admission.isCurrent()) return;
                    MusicHud.EXECUTOR.execute(() -> {
                    if (!admission.isCurrent()) return;
                    ResolvePlaybackResultMessage result;
                    try {
                        PlaybackResolution resolution = IClientMusicService.getInstance()
                                .resolvePublicPlayback(message.requestedMusic());
                        result = ResolvePlaybackResultMessage.success(
                                message.requestId(), message.revision(), resolution);
                    } catch (Exception ignored) {
                        result = ResolvePlaybackResultMessage.failure(
                                message.requestId(), message.revision(), "resolve_failed");
                    }
                    ResolvePlaybackResultMessage completed = result;
                    admission.runIfCurrent(() -> IClientNetworkService.getInstance().sendToServer(completed));
                    });
                };
            }
            INetworkRegister.getInstance().autoRegisterPayload(
                    ResolvePlaybackRequestMessage.class, CODEC, receiver);
        }
    }
}
