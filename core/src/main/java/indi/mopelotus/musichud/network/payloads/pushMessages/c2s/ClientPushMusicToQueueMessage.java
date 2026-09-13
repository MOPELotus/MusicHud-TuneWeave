package indi.mopelotus.musichud.network.payloads.pushMessages.c2s;

import indi.mopelotus.musichud.beans.music.PusherInfo;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.server.api.MusicPlayerServerService;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;

public record ClientPushMusicToQueueMessage(MusicDetail musicDetail) implements C2SPayload {
    public static final ByteBufCodec<ClientPushMusicToQueueMessage> CODEC = ByteBufCodec.composite(
            MusicDetail.CODEC,
            ClientPushMusicToQueueMessage::musicDetail,
            ClientPushMusicToQueueMessage::new
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    ClientPushMusicToQueueMessage.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((message, player) -> {
                        MusicPlayerServerService.getInstance().pushMusicToQueue(
                                message.musicDetail, PusherInfo.ofPlayer(player));
                    })
            );
        }
    }
}
