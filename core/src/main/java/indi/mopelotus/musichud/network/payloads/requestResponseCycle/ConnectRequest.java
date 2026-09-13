package indi.mopelotus.musichud.network.payloads.requestResponseCycle;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.Version;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.IServerNetworkService;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.network.ProtocolCapability;
import indi.mopelotus.musichud.network.ProtocolInfo;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.platform.Environment;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;

import java.util.Set;
import java.util.Objects;

public record ConnectRequest(String projectId, Version clientVersion,
                             Set<ProtocolCapability> capabilities) implements C2SPayload {
    public static final ByteBufCodec<ConnectRequest> CODEC =
            ByteBufCodec.composite(
                    Codecs.STRING_UTF8, ConnectRequest::projectId,
                    Version.PACKET_CODEC, ConnectRequest::clientVersion,
                    Codecs.ofSet(() -> Codecs.ofEnum(ProtocolCapability.class)),
                    ConnectRequest::capabilities,
                    ConnectRequest::new);

    public ConnectRequest {
        projectId = Objects.requireNonNullElse(projectId, "");
        Objects.requireNonNull(clientVersion, "clientVersion");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    public static ConnectRequest current() {
        return new ConnectRequest(ProtocolInfo.PROJECT_ID, Version.CURRENT, ProtocolInfo.CAPABILITIES);
    }

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        private static ClientConfig clientConfig;

        static {
            if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
                try {
                    clientConfig = ClientConfig.getInstance();
                } catch (UnsupportedOperationException e) {
                    clientConfig = null;
                }
            }
        }

        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    ConnectRequest.class, CODEC,
                    ServerDataPacketVThreadExecutor.executeControl((startQRLoginRequest, player) -> {
                        boolean compatible = ProtocolInfo.isCompatible(
                                startQRLoginRequest.projectId(), startQRLoginRequest.clientVersion(),
                                startQRLoginRequest.capabilities());
                        MusicHud.LOGGER.info("TuneWeave protocol handshake from {}: compatible={} side={}",
                                player.getUUID(), compatible, MusicHud.getCurrentEnvironment().getSide());
                        if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT && !clientConfig.getEnabledInIntegratedServer()) {
                            if (compatible) {
                                ServerPlayerRegistry.getInstance().join(player);
                            }
                            return;
                        }
                        if (compatible) {
                            ServerPlayerRegistry.getInstance().join(player);
                        }
                        // Register before acknowledging so the client cannot send its first
                        // operation while absent from the server broadcast membership.
                        indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectResponse response =
                                ConnectResponse.current(compatible);
                        IServerNetworkService.getInstance().sendToPlayer(player, response);
                        MusicHud.LOGGER.debug("TuneWeave protocol handshake response sent to {} accepted={} registrySize={}",
                                player.getUUID(), compatible, ServerPlayerRegistry.getInstance().players().size());
                    })
            );
        }
    }
}
