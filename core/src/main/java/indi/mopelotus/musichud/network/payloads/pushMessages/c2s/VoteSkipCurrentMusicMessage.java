package indi.mopelotus.musichud.network.payloads.pushMessages.c2s;

import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.server.api.MusicPlayerServerService;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;

public record VoteSkipCurrentMusicMessage(long id) implements C2SPayload {
    public static final ByteBufCodec<VoteSkipCurrentMusicMessage> CODEC = ByteBufCodec.composite(
            Codecs.LONG,
            VoteSkipCurrentMusicMessage::id,
            VoteSkipCurrentMusicMessage::new
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    VoteSkipCurrentMusicMessage.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((message, player) -> {
                        MusicPlayerServerService.getInstance().voteSkipCurrent(message.id, player.getUUID());
                    })
            );
        }
    }
}
