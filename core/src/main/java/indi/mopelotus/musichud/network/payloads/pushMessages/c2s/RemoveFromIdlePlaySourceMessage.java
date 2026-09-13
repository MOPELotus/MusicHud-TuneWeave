package indi.mopelotus.musichud.network.payloads.pushMessages.c2s;

import indi.mopelotus.musichud.beans.api.IdlePlaySource;
import indi.mopelotus.musichud.beans.music.PusherInfo;
import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.server.api.MusicPlayerServerService;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;

public record RemoveFromIdlePlaySourceMessage(IdlePlaySource idlePlaySource) implements C2SPayload {
    public static final ByteBufCodec<RemoveFromIdlePlaySourceMessage> CODEC = ByteBufCodec.composite(
            IdlePlaySource.CODEC,
            RemoveFromIdlePlaySourceMessage::idlePlaySource,
            RemoveFromIdlePlaySourceMessage::new
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    RemoveFromIdlePlaySourceMessage.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((message, player) -> {
                        IdlePlaySource idlePlaySource = message.idlePlaySource;
                        MusicPlayerServerService.getInstance().removeIdlePlaySource(idlePlaySource.getId(), idlePlaySource.getType(), PusherInfo.ofPlayer(player));
                    })
            );
        }
    }
}