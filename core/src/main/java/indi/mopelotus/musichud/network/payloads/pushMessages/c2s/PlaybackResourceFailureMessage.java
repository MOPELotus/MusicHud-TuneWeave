package indi.mopelotus.musichud.network.payloads.pushMessages.c2s;

import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.server.api.MusicPlayerServerService;

import java.util.UUID;

public record PlaybackResourceFailureMessage(UUID sessionId, int revision) implements C2SPayload {
    public static final ByteBufCodec<PlaybackResourceFailureMessage> CODEC = ByteBufCodec.composite(
            Codecs.UUID, PlaybackResourceFailureMessage::sessionId,
            Codecs.INT, PlaybackResourceFailureMessage::revision,
            PlaybackResourceFailureMessage::new
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        @Override
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    PlaybackResourceFailureMessage.class, CODEC,
                    (message, player) -> MusicPlayerServerService.getInstance()
                            .reportPlaybackResourceFailure(player, message));
        }
    }
}
