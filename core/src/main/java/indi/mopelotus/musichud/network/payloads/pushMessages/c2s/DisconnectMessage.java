package indi.mopelotus.musichud.network.payloads.pushMessages.c2s;

import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;

public final class DisconnectMessage implements C2SPayload {
    public static final DisconnectMessage INSTANCE = new DisconnectMessage();
    public static final ByteBufCodec<DisconnectMessage> CODEC = ByteBufCodec.unit(INSTANCE);

    private DisconnectMessage() {
    }

    @RegisterMark
    public static final class RegisterImpl implements CommonRegister {
        @Override
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    DisconnectMessage.class, CODEC,
                    ServerDataPacketVThreadExecutor.executeControl((message, player) ->
                            ServerPlayerRegistry.getInstance().leave(player)));
        }
    }
}
