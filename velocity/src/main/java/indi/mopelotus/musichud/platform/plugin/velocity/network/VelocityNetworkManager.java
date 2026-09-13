package indi.mopelotus.musichud.platform.plugin.velocity.network;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.network.IServerNetworkService;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.network.payloads.IPayload;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityNetworkManager implements INetworkRegister, IServerNetworkService, AutoCloseable {
    private static final VelocityNetworkManager INSTANCE = new VelocityNetworkManager();

    private final Logger logger = MusicHud.getLogger(VelocityNetworkManager.class);
    private final Map<String, RegisteredReceiver<?>> c2sReceivers = new ConcurrentHashMap<>();
    private final Map<String, ByteBufCodec<?>> s2cCodecs = new ConcurrentHashMap<>();
    private final Map<String, ChannelIdentifier> channels = new ConcurrentHashMap<>();
    private volatile ProxyServer proxy;
    public boolean isOpen() { return proxy != null; }
    public boolean isCurrentConnection(Player player) {
        ProxyServer owner = proxy;
        return owner != null && owner.getPlayer(player.getUniqueId()).orElse(null) == player && player.isActive();
    }

    private VelocityNetworkManager() {
    }

    public static VelocityNetworkManager getInstance() {
        return INSTANCE;
    }

    public void initialize(ProxyServer proxy) {
        this.proxy = proxy;
        registerChannel(indi.mopelotus.musichud.network.ProxyDeploymentProbe.CHANNEL);
    }

    @Override
    public <T extends IPayload> void registerC2SPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> serverReceiver
    ) {
        indi.mopelotus.musichud.network.PayloadFragments.register(clazz, codec, serverReceiver, true);
        String channel = channelId(clazz);
        c2sReceivers.put(channel, new RegisteredReceiver<>(codec, serverReceiver));
        registerChannel(channel);
    }

    @Override
    public <T extends IPayload> void registerS2CPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientReceiver
    ) {
        indi.mopelotus.musichud.network.PayloadFragments.register(clazz, codec, clientReceiver, false);
        String channel = channelId(clazz);
        s2cCodecs.put(channel, codec);
        registerChannel(channel);
    }

    @Override
    public <T extends IPayload> void autoRegisterPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> receiver
    ) {
        if (S2CPayload.class.isAssignableFrom(clazz)) {
            registerS2CPayload(clazz, codec, receiver);
            return;
        }
        if (C2SPayload.class.isAssignableFrom(clazz)) {
            registerC2SPayload(clazz, codec, receiver);
            return;
        }
        throw new IllegalArgumentException("Payload class must implement S2CPayload or C2SPayload");
    }

    @Override
    public void sendToPlayer(IPlayerClient playerClient, S2CPayload payload) {
        indi.mopelotus.musichud.network.PayloadFragments.sendS2C(payload, part -> sendRawToPlayer(playerClient, part));
    }

    private void sendRawToPlayer(IPlayerClient playerClient, S2CPayload payload) {
        ProxyServer activeProxy = proxy;
        if (activeProxy == null) return;
        Player player = activeProxy.getPlayer(playerClient.getUUID()).orElse(null);
        if (player == null || !playerClient.isConnected() || playerClient.connectionIdentity() != player) {
            logger.warn("Skipping payload for offline player {}", playerClient.getName());
            return;
        }

        String channel = channelId(payload.getClass());
        @SuppressWarnings("unchecked")
        ByteBufCodec<S2CPayload> codec = (ByteBufCodec<S2CPayload>) s2cCodecs.get(channel);
        if (codec == null) {
            logger.warn("Skipping unregistered S2C payload {}", channel);
            return;
        }

        ByteBuf buffer = Unpooled.buffer();
        byte[] bytes;
        try {
            codec.encode(buffer, payload);
            bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
        } finally {
            buffer.release();
        }

        ChannelIdentifier identifier = registerChannel(channel);
        if (!player.sendPluginMessage(identifier, bytes)) {
            logger.debug("Velocity did not accept plugin message {} for {}", channel, player.getUsername());
        }
    }

    public void probeBackend(Player player) {
        ProxyServer owner = proxy;
        if (owner == null || owner.getPlayer(player.getUniqueId()).orElse(null) != player
                || !VelocityPlayerProxy.of(player).isConnected()) return;
        player.getCurrentServer().ifPresent(backend -> backend.sendPluginMessage(
                registerChannel(indi.mopelotus.musichud.network.ProxyDeploymentProbe.CHANNEL),
                indi.mopelotus.musichud.network.ProxyDeploymentProbe.request()));
    }

    public boolean isServerChannel(String channel) {
        return s2cCodecs.containsKey(channel);
    }

    public boolean handles(String channel) {
        return c2sReceivers.containsKey(channel);
    }

    public void handle(Player player, String channel, byte[] bytes) {
        RegisteredReceiver<?> receiver = c2sReceivers.get(channel);
        if (receiver == null) {
            return;
        }
        try {
            receiver.receive(bytes, VelocityPlayerProxy.of(player));
        } catch (Exception exception) {
            logger.error("Failed to process Velocity payload on channel {}", channel, exception);
        }
    }

    @Override
    public void close() {
        ProxyServer activeProxy = proxy;
        if (activeProxy != null && !channels.isEmpty()) {
            activeProxy.getChannelRegistrar().unregister(channels.values().toArray(ChannelIdentifier[]::new));
        }
        c2sReceivers.clear();
        s2cCodecs.clear();
        channels.clear();
        proxy = null;
    }

    private ChannelIdentifier registerChannel(String channel) {
        return channels.computeIfAbsent(channel, key -> {
            ChannelIdentifier identifier = MinecraftChannelIdentifier.from(key);
            requireProxy().getChannelRegistrar().register(identifier);
            return identifier;
        });
    }

    private ProxyServer requireProxy() {
        ProxyServer activeProxy = proxy;
        if (activeProxy == null) {
            throw new IllegalStateException("Velocity network manager is not initialized");
        }
        return activeProxy;
    }

    private static String channelId(Class<?> clazz) {
        return indi.mopelotus.musichud.network.ProtocolChannels.id(clazz);
    }

    private record RegisteredReceiver<T extends IPayload>(ByteBufCodec<T> codec, NetworkReceiver<T> receiver) {
        private void receive(byte[] bytes, IPlayerClient player) {
            if (bytes.length > 1_048_576 || !player.isConnected()) return;
            ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
            try {
                T payload = codec.decode(buffer);
                if (buffer.isReadable()) throw new IllegalArgumentException("Trailing payload bytes");
                receiver.receive(payload, player);
            } finally {
                buffer.release();
            }
        }
    }
}
