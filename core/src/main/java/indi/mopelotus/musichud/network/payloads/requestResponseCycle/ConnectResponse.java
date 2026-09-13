package indi.mopelotus.musichud.network.payloads.requestResponseCycle;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.Version;
import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.IConnectionManager;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.ProtocolCapability;
import indi.mopelotus.musichud.network.ProtocolInfo;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.platform.Environment;

import java.util.Objects;
import java.util.Set;


public record ConnectResponse(boolean accepted, String projectId, Version serverVersion,
                              Set<ProtocolCapability> capabilities) implements S2CPayload {
    public static final ByteBufCodec<ConnectResponse> CODEC =
            ByteBufCodec.composite(
                    Codecs.BOOL,
                    ConnectResponse::accepted,
                    Codecs.STRING_UTF8,
                    ConnectResponse::projectId,
                    Version.PACKET_CODEC,
                    ConnectResponse::serverVersion,
                    Codecs.ofSet(() -> Codecs.ofEnum(ProtocolCapability.class)),
                    ConnectResponse::capabilities,
                    ConnectResponse::new
            );

    public ConnectResponse {
        projectId = Objects.requireNonNullElse(projectId, "");
        Objects.requireNonNull(serverVersion, "serverVersion");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    public static ConnectResponse current(boolean accepted) {
        return new ConnectResponse(accepted, ProtocolInfo.PROJECT_ID, Version.CURRENT,
                ProtocolInfo.CAPABILITIES);
    }

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            NetworkReceiver<ConnectResponse> receiver = NetworkReceiver.noop();
            if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
                receiver = (payload, player) -> IConnectionManager.getInstance().onConnectResponse(payload, player);
            }
            INetworkRegister.getInstance().autoRegisterPayload(
                    ConnectResponse.class, CODEC,
                    receiver
            );
        }
    }
}
