package indi.mopelotus.musichud.network.payloads.pushMessages.c2s;

import indi.mopelotus.musichud.interfaces.*;
import indi.mopelotus.musichud.network.*;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;

public record ClientPayloadFragment(PayloadFragments.Frame frame) implements C2SPayload {
    public static final ByteBufCodec<ClientPayloadFragment> CODEC = ByteBufCodec.composite(PayloadFragments.CODEC,
            ClientPayloadFragment::frame, ClientPayloadFragment::new);
    @RegisterMark public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(ClientPayloadFragment.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((payload, player) -> PayloadFragments.receive(payload.frame(), player, true)));
        }
    }
}
