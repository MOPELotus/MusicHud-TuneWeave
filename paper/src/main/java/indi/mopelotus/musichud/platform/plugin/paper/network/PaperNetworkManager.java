package indi.mopelotus.musichud.platform.plugin.paper.network;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.*;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.network.payloads.IPayload;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PaperNetworkManager implements INetworkRegister, IServerNetworkService, AutoCloseable {
    private static final int MAX_PLUGIN_MESSAGE_RETRY_TICKS = 40;
    private static volatile PaperNetworkManager instance;

    private final Logger logger = MusicHud.getLogger(PaperNetworkManager.class);
    private final Map<String, RegisteredReceiver<?>> c2sReceivers = new ConcurrentHashMap<>();
    private final Map<String, ByteBufCodec<?>> s2cCodecs = new ConcurrentHashMap<>();
    private final Set<String> incomingChannels = ConcurrentHashMap.newKeySet();
    private final Set<String> outgoingChannels = ConcurrentHashMap.newKeySet();
    private volatile JavaPlugin plugin;
    private final java.util.concurrent.atomic.AtomicLong lifecycle = new java.util.concurrent.atomic.AtomicLong();
    public boolean isOpen() { return plugin != null; }

    private PaperNetworkManager() {
    }

    public static PaperNetworkManager getInstance() {
        if (instance == null) {
            synchronized (PaperNetworkManager.class) {
                if (instance == null) {
                    instance = new PaperNetworkManager();
                }
            }
        }
        return instance;
    }

    public void initialize(JavaPlugin plugin) {
        if (this.plugin == plugin) {
            return;
        }
        this.plugin = plugin;
        long generation = lifecycle.incrementAndGet();
        String channel = ProxyDeploymentProbe.CHANNEL;
        incomingChannels.add(channel);
        ensureOutgoingChannelRegistered(channel);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, channel, (received, player, bytes) -> {
            if (!channel.equals(received) || !ProxyDeploymentProbe.isRequest(bytes)) return;
            // This is public presence information, never permission to disable a standalone server.
            player.getScheduler().run(plugin, task -> new DeferredPluginSend(
                    () -> this.plugin == plugin && lifecycle.get() == generation && plugin.isEnabled() && player.isOnline(),
                    () -> player.getListeningPluginChannels().contains(channel),
                    () -> player.sendPluginMessage(plugin, channel, ProxyDeploymentProbe.present()),
                    later -> player.getScheduler().runDelayed(plugin, ignored -> later.run(), null, 1L),
                    MAX_PLUGIN_MESSAGE_RETRY_TICKS).run(), null);
        });
    }

    @Override
    public <T extends IPayload> void registerC2SPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> serverReceiver
    ) {
        PayloadFragments.register(clazz, codec, serverReceiver, true);
        String channel = getChannelId(clazz);
        c2sReceivers.put(channel, new RegisteredReceiver<>(codec, serverReceiver));
        ensureIncomingChannelRegistered(channel);
    }

    @Override
    public <T extends IPayload> void registerS2CPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientReceiver
    ) {
        PayloadFragments.register(clazz, codec, clientReceiver, false);
        String channel = getChannelId(clazz);
        s2cCodecs.put(channel, codec);
        ensureOutgoingChannelRegistered(channel);
    }

    @Override
    public <T extends IPayload> void autoRegisterPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientOrServerReceiver
    ) {
        if (S2CPayload.class.isAssignableFrom(clazz)) {
            registerS2CPayload(clazz, codec, clientOrServerReceiver);
            return;
        }
        if (C2SPayload.class.isAssignableFrom(clazz)) {
            registerC2SPayload(clazz, codec, clientOrServerReceiver);
            return;
        }
        throw new IllegalArgumentException("Payload class must implement S2CPayload or C2SPayload");
    }

    @Override
    public void sendToPlayer(IPlayerClient playerClient, S2CPayload payload) {
        PayloadFragments.sendS2C(payload, part -> sendRawToPlayer(playerClient, part));
    }

    private void sendRawToPlayer(IPlayerClient playerClient, S2CPayload payload) {
        if (plugin == null) return;
        String channel = getChannelId(payload.getClass());
        @SuppressWarnings("unchecked")
        ByteBufCodec<S2CPayload> codec = (ByteBufCodec<S2CPayload>) s2cCodecs.get(channel);
        if (codec == null) {
            logger.warn("Skipping unregistered S2C payload {}", channel);
            return;
        }
        Player bukkitPlayer = Bukkit.getPlayer(playerClient.getUUID());
        if (bukkitPlayer == null || !playerClient.isConnected() || playerClient.connectionIdentity() != bukkitPlayer) {
            logger.warn("Skipping {} because player {} is no longer online", channel, playerClient.getName());
            return;
        }
        byte[] result;
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, payload);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            result = bytes;
        } finally {
            buffer.release();
        }
        byte[] networkPayload = result;
        ensureOutgoingChannelRegistered(channel);
        JavaPlugin owner = plugin;
        long generation = lifecycle.get();
        if (owner != null) bukkitPlayer.getScheduler().run(owner,
                task -> new DeferredPluginSend(
                        () -> plugin == owner && lifecycle.get() == generation && owner.isEnabled() && bukkitPlayer.isOnline() && playerClient.isConnected(),
                        () -> bukkitPlayer.getListeningPluginChannels().contains(channel),
                        () -> bukkitPlayer.sendPluginMessage(owner, channel, networkPayload),
                        later -> bukkitPlayer.getScheduler().runDelayed(owner, ignored -> later.run(), null, 1L),
                        MAX_PLUGIN_MESSAGE_RETRY_TICKS).run(), null);
    }

    @Override
    public void close() {
        lifecycle.incrementAndGet();
        if (plugin == null) {
            return;
        }
        Messenger messenger = plugin.getServer().getMessenger();
        for (String channel : incomingChannels) {
            messenger.unregisterIncomingPluginChannel(plugin, channel);
        }
        for (String channel : outgoingChannels) {
            messenger.unregisterOutgoingPluginChannel(plugin, channel);
        }
        incomingChannels.clear();
        outgoingChannels.clear();
        c2sReceivers.clear();
        s2cCodecs.clear();
        plugin = null;
    }

    private static String getChannelId(Class<?> clazz) {
        return indi.mopelotus.musichud.network.ProtocolChannels.id(clazz);
    }

    private void ensureIncomingChannelRegistered(String channel) {
        if (incomingChannels.add(channel)) {
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, channel, createListener(channel));
        }
    }

    private void ensureOutgoingChannelRegistered(String channel) {
        if (outgoingChannels.add(channel)) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, channel);
        }
    }

    private PluginMessageListener createListener(String expectedChannel) {
        return (channel, player, message) -> {
            if (!expectedChannel.equals(channel)) {
                return;
            }
            RegisteredReceiver<?> registered = c2sReceivers.get(channel);
            if (registered == null) {
                return;
            }
            try {
                registered.receive(message, PaperPlayerProxy.ofPlayer(player));
            } catch (Exception e) {
                logger.error("Failed to process payload on channel {}", channel, e);
            }
        };
    }

    private record RegisteredReceiver<T extends IPayload>(
            ByteBufCodec<T> codec,
            NetworkReceiver<T> receiver
    ) {
        private void receive(byte[] bytes, IPlayerClient player) {
            if (bytes.length > 1_048_576 || !player.isConnected()) return;
            T result;
            ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
            try {
                result = codec.decode(buffer);
                if (buffer.isReadable()) throw new IllegalArgumentException("Trailing payload bytes");
            } finally {
                buffer.release();
            }
            T payload = result;
            receiver.receive(payload, player);
        }
    }
}
