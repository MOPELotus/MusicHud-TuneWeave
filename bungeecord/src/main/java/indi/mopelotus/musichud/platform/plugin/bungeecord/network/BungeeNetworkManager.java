package indi.mopelotus.musichud.platform.plugin.bungeecord.network;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.ProxyServer;
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

public final class BungeeNetworkManager implements INetworkRegister, IServerNetworkService, AutoCloseable {
    private static final BungeeNetworkManager INSTANCE = new BungeeNetworkManager();

    private final Logger logger = MusicHud.getLogger(BungeeNetworkManager.class);
    private final Map<String, RegisteredReceiver<?>> c2sReceivers = new ConcurrentHashMap<>();
    private final Map<String, ByteBufCodec<?>> s2cCodecs = new ConcurrentHashMap<>();
    private final Map<String, String> channels = new ConcurrentHashMap<>();
    private volatile ProxyServer proxy;
    private BungeePlayGate playGate = new BungeePlayGate();
    private java.util.function.BiFunction<ProxiedPlayer, net.md_5.bungee.api.connection.Server, BungeePlayGate.Transport> transports;

    public boolean isOpen() { return proxy != null; }
    public boolean isCurrentConnection(ProxiedPlayer player) {
        ProxyServer owner = proxy;
        return owner != null && owner.getPlayer(player.getUniqueId()) == player && player.isConnected();
    }

    private BungeeNetworkManager() {
    }

    public static BungeeNetworkManager getInstance() {
        return INSTANCE;
    }

    public void initialize(ProxyServer proxy) { initialize(proxy, BungeeNativeTransport::new); }

    public void initialize(ProxyServer proxy,
            java.util.function.BiFunction<ProxiedPlayer, net.md_5.bungee.api.connection.Server, BungeePlayGate.Transport> transports) {
        playGate.close();
        playGate = new BungeePlayGate();
        this.transports = transports;
        this.proxy = proxy;
        registerChannel(indi.mopelotus.musichud.network.ProxyDeploymentProbe.CHANNEL);
    }

    public void afterBackendReady(ProxiedPlayer player, int bytes, Runnable send) {
        afterBackendReady(player, bytes, active -> send.run());
    }

    private void afterBackendReady(ProxiedPlayer player, int bytes,
                                  java.util.function.Consumer<java.util.function.BooleanSupplier> send) {
        ProxyServer owner = proxy;
        var backend = player.getServer();
        if (owner == null || backend == null || !BungeePlayerProxy.of(player).isConnected()) return;
        java.util.function.BooleanSupplier active = () -> proxy == owner && player.getServer() == backend
                && backend.isConnected() && BungeePlayerProxy.of(player).isConnected();
        playGate.submit(player, backend, active,
                transports.apply(player, backend), bytes, () -> send.accept(active), () -> {
                    BungeePlayerProxy.disconnect(player);
                    indi.mopelotus.musichud.server.ServerPlayerRegistry.getInstance().disconnect(BungeePlayerProxy.of(player));
                    try { indi.mopelotus.musichud.platform.plugin.bungeecord.event.BungeeEventService.getInstance().firePlayerQuit(player); }
                    finally { player.disconnect(new net.md_5.bungee.api.chat.TextComponent(
                            "MusicHud TuneWeave: too many packets pending while switching servers. Please reconnect.")); }
                });
    }

    public void cancelPending(ProxiedPlayer player) { playGate.cancel(player); }

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
        ProxyServer activeProxy = proxy;
        if (activeProxy == null) return;
        ProxiedPlayer player = activeProxy.getPlayer(playerClient.getUUID());
        if (player == null || !playerClient.isConnected() || playerClient.connectionIdentity() != player) {
            logger.warn("Skipping payload for offline player {}", playerClient.getName());
            return;
        }

        // Encode the complete logical message before queueing: CONFIGURATION must not
        // count its fragments as separate messages or enqueue only part of a payload.
        var packets = new java.util.ArrayList<EncodedPacket>();
        indi.mopelotus.musichud.network.PayloadFragments.sendS2C(payload, part -> packets.add(encodePacket(part)));
        if (packets.stream().anyMatch(java.util.Objects::isNull)) return;
        int size = 0;
        for (var packet : packets) size = Math.addExact(size, packet.bytes.length);
        afterBackendReady(player, size, active -> {
            for (var packet : packets) {
                if (!active.getAsBoolean()) return;
                player.sendData(packet.channel, packet.bytes);
            }
        });
    }

    private record EncodedPacket(String channel, byte[] bytes) {}

    private EncodedPacket encodePacket(S2CPayload payload) {
        String channel = channelId(payload.getClass());
        @SuppressWarnings("unchecked")
        ByteBufCodec<S2CPayload> codec = (ByteBufCodec<S2CPayload>) s2cCodecs.get(channel);
        if (codec == null) {
            logger.warn("Skipping unregistered S2C payload {}", channel);
            return null;
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

        String registered = registerChannel(channel);
        return new EncodedPacket(registered, bytes);
    }


    public void probeBackend(ProxiedPlayer player) {
        ProxyServer owner = proxy;
        if (owner == null || owner.getPlayer(player.getUniqueId()) != player
                || !BungeePlayerProxy.of(player).isConnected()) return;
        var backend = player.getServer();
        if (backend != null) {
            String channel = registerChannel(indi.mopelotus.musichud.network.ProxyDeploymentProbe.CHANNEL);
            afterBackendReady(player, 5, () -> backend.sendData(channel,
                    indi.mopelotus.musichud.network.ProxyDeploymentProbe.request()));
        }
    }

    public boolean isServerChannel(String channel) {
        return s2cCodecs.containsKey(channel);
    }

    public boolean handles(String channel) {
        return c2sReceivers.containsKey(channel);
    }

    public void handle(ProxiedPlayer player, String channel, byte[] bytes) {
        RegisteredReceiver<?> receiver = c2sReceivers.get(channel);
        if (receiver == null) {
            return;
        }
        try {
            receiver.receive(bytes, BungeePlayerProxy.of(player));
        } catch (Exception exception) {
            logger.error("Failed to process Bungee payload on channel {}", channel, exception);
        }
    }

    @Override
    public void close() {
        ProxyServer activeProxy = proxy;
        proxy = null;
        playGate.close();
        if (activeProxy != null && !channels.isEmpty()) {
            channels.values().forEach(activeProxy::unregisterChannel);
        }
        c2sReceivers.clear();
        s2cCodecs.clear();
        channels.clear();
        proxy = null;
    }

    private String registerChannel(String channel) {
        return channels.computeIfAbsent(channel, key -> {
            requireProxy().registerChannel(key);
            return key;
        });
    }

    private ProxyServer requireProxy() {
        ProxyServer activeProxy = proxy;
        if (activeProxy == null) {
            throw new IllegalStateException("Bungee network manager is not initialized");
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
