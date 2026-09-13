package indi.mopelotus.musichud.network.payloads.pushMessages.c2s;

import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.server.api.MusicPlayerServerService;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;

import java.util.UUID;

public record ClientRemoveMusicFromQueueMessage(int index, long id, UUID queueUniqueID) implements C2SPayload {
    public static final ByteBufCodec<ClientRemoveMusicFromQueueMessage> CODEC = ByteBufCodec.composite(
            Codecs.INT,
            ClientRemoveMusicFromQueueMessage::index,
            Codecs.LONG,
            ClientRemoveMusicFromQueueMessage::id,
            Codecs.UUID,
            ClientRemoveMusicFromQueueMessage::queueUniqueID,
            ClientRemoveMusicFromQueueMessage::new
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    ClientRemoveMusicFromQueueMessage.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((message, player) -> {
                            MusicPlayerServerService.getInstance().removeMusicDetailFromQueue(
                                    message.index, message.id, message.queueUniqueID, player.getUUID()
                            );
                    })
            );
        }
    }
}
