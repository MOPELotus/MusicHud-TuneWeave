package indi.mopelotus.musichud.platform.mod.network;

import indi.mopelotus.musichud.network.ProxyDeploymentProbe;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Fixed public presence marker, independent of the playback handshake and fragmentation. */
public record DeploymentProbePayload(boolean request, boolean present) implements CustomPacketPayload {
    public static final Type<DeploymentProbePayload> TYPE = new Type<>(ResourceLocation.parse(ProxyDeploymentProbe.CHANNEL));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeploymentProbePayload> CODEC = new StreamCodec<>() {
        @Override public DeploymentProbePayload decode(RegistryFriendlyByteBuf input) {
            if (input.readableBytes() != 5) {
                input.skipBytes(input.readableBytes());
                return new DeploymentProbePayload(false, false);
            }
            byte[] marker = new byte[5];
            input.readBytes(marker);
            return new DeploymentProbePayload(ProxyDeploymentProbe.isRequest(marker), ProxyDeploymentProbe.isPresent(marker));
        }
        @Override public void encode(RegistryFriendlyByteBuf output, DeploymentProbePayload payload) {
            if (payload.request() == payload.present()) throw new IllegalArgumentException("Invalid deployment marker");
            output.writeBytes(payload.request() ? ProxyDeploymentProbe.request() : ProxyDeploymentProbe.present());
        }
    };
    public static DeploymentProbePayload presence() { return new DeploymentProbePayload(false, true); }
    @Override public Type<DeploymentProbePayload> type() { return TYPE; }
}
