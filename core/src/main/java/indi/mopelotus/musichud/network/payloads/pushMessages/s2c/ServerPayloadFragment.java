package indi.mopelotus.musichud.network.payloads.pushMessages.s2c;

import indi.mopelotus.musichud.interfaces.*;
import indi.mopelotus.musichud.network.*;
import indi.mopelotus.musichud.network.payloads.S2CPayload;

public record ServerPayloadFragment(PayloadFragments.Frame frame) implements S2CPayload {
    public static final ByteBufCodec<ServerPayloadFragment> CODEC = ByteBufCodec.composite(PayloadFragments.CODEC,
            ServerPayloadFragment::frame, ServerPayloadFragment::new);
    @RegisterMark public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(ServerPayloadFragment.class, CODEC,
                    (payload, player) -> PayloadFragments.receive(payload.frame(), player, false));
        }
    }
}
