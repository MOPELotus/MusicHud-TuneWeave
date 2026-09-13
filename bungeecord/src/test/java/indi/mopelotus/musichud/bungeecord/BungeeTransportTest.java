package indi.mopelotus.musichud.bungeecord;

import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.*;

import indi.mopelotus.musichud.network.*;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.ServerPayloadFragment;
import indi.mopelotus.musichud.platform.plugin.bungeecord.network.*;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class BungeeTransportTest {
    @TempDir Path directory;

    @Test void configurationQueuesCompleteLegalMessagesThroughMaximumProtocolSize() {
        for (int size : new int[]{2_400_000, PayloadFragments.MAX_BYTES}) {
            var manager = BungeeNetworkManager.getInstance();
            var sent = new ArrayList<byte[]>();
            var peer = player(UUID.randomUUID(), sent);
            var transport = new ManualTransport();
            manager.initialize(proxy(new AtomicReference<>(peer)), (p, b) -> transport);
            try {
                manager.registerS2CPayload(BinaryPayload.class, BINARY_CODEC, NetworkReceiver.noop());
                manager.registerS2CPayload(ServerPayloadFragment.class, ServerPayloadFragment.CODEC, NetworkReceiver.noop());
                byte[] content = new byte[size];
                new Random(42).nextBytes(content);
                manager.sendToPlayer(BungeePlayerProxy.of(peer), new BinaryPayload(content));
                transport.tick();
                assertTrue(sent.isEmpty());
                transport.play = true;
                transport.tick();
                assertEquals((size + PayloadFragments.CHUNK_BYTES - 1) / PayloadFragments.CHUNK_BYTES, sent.size());
                var assembler = new PayloadFragments.Assembler();
                byte[] decoded = null;
                for (int i = 0; i < sent.size(); i++) {
                    assertTrue(sent.get(i).length < 32766);
                    var buffer = Unpooled.wrappedBuffer(sent.get(i));
                    try {
                        var frame = ServerPayloadFragment.CODEC.decode(buffer).frame();
                        assertEquals(i, frame.index());
                        assertFalse(buffer.isReadable());
                        decoded = assembler.accept(peer, frame, 1);
                    } finally { buffer.release(); }
                }
                assertArrayEquals(content, decoded);
            } finally { manager.close(); }
        }
    }

    @Test void oversizedLogicalMessageDoesNotQueueAnyFragments() {
        var manager = BungeeNetworkManager.getInstance();
        var sent = new ArrayList<byte[]>();
        var peer = player(UUID.randomUUID(), sent);
        var transport = new ManualTransport();
        manager.initialize(proxy(new AtomicReference<>(peer)), (p, b) -> transport);
        try {
            manager.registerS2CPayload(BinaryPayload.class, BINARY_CODEC, NetworkReceiver.noop());
            manager.registerS2CPayload(ServerPayloadFragment.class, ServerPayloadFragment.CODEC, NetworkReceiver.noop());
            assertThrows(IndexOutOfBoundsException.class, () -> manager.sendToPlayer(BungeePlayerProxy.of(peer),
                    new BinaryPayload(new byte[PayloadFragments.MAX_BYTES + 1])));
            transport.play = true;
            transport.tick();
            assertTrue(sent.isEmpty());
            manager.sendToPlayer(BungeePlayerProxy.of(peer), new BinaryPayload(new byte[]{1, 2, 3}));
            transport.tick();
            assertEquals(1, sent.size());
            assertArrayEquals(new byte[]{1, 2, 3}, sent.getFirst());
        } finally { manager.close(); }
    }

    @Test void connectionReplacementDuringFragmentDeliveryStopsTheRestOfTheMessage() {
        var manager = BungeeNetworkManager.getInstance();
        var sent = new ArrayList<byte[]>() {
            @Override public boolean add(byte[] packet) {
                boolean added = super.add(packet);
                if (size() == 1) onFirst.run();
                return added;
            }
            Runnable onFirst;
        };
        UUID id = UUID.randomUUID();
        var peer = player(id, sent);
        var active = new AtomicReference<>(peer);
        sent.onFirst = () -> active.set(player(id, new ArrayList<>()));
        manager.initialize(proxy(active), (p, b) -> TestProxy.ready());
        try {
            manager.registerS2CPayload(BinaryPayload.class, BINARY_CODEC, NetworkReceiver.noop());
            manager.registerS2CPayload(ServerPayloadFragment.class, ServerPayloadFragment.CODEC, NetworkReceiver.noop());
            manager.sendToPlayer(BungeePlayerProxy.of(peer), new BinaryPayload(new byte[48_001]));
            assertEquals(1, sent.size(), "retired physical connection must not receive the remaining fragments");
        } finally { manager.close(); }
    }

    @Test void actualAdapterFragmentsLargePayloadAndRejectsReplacedConnection() {
        var manager = BungeeNetworkManager.getInstance();
        UUID id = UUID.randomUUID(); List<byte[]> sent = new ArrayList<>();
        ProxiedPlayer first = player(id, sent), replacement = player(id, sent);
        var active = new AtomicReference<>(first);
        manager.initialize(proxy(active), (p, b) -> TestProxy.ready());
        try {
            manager.registerS2CPayload(LargePayload.class, ByteBufCodec.composite(Codecs.STRING_UTF8, LargePayload::value, LargePayload::new), NetworkReceiver.noop());
            manager.registerS2CPayload(ServerPayloadFragment.class, ServerPayloadFragment.CODEC, NetworkReceiver.noop());
            var old = BungeePlayerProxy.of(first);
            manager.sendToPlayer(old, new LargePayload("x".repeat(30_000)));
            assertEquals(2, sent.size());
            for (byte[] bytes : sent) {
                assertTrue(bytes.length < 32766);
                var buffer = Unpooled.wrappedBuffer(bytes);
                try { assertNotNull(ServerPayloadFragment.CODEC.decode(buffer)); assertFalse(buffer.isReadable()); }
                finally { buffer.release(); }
            }
            active.set(replacement);
            manager.sendToPlayer(old, new LargePayload("old"));
            assertEquals(2, sent.size());
            BungeePlayerProxy.disconnect(first);
            assertFalse(old.isConnected());
            assertTrue(BungeePlayerProxy.of(replacement).isConnected());
        } finally { manager.close(); }
    }

    @Test void actualPluginEventDropsBackendAuthorityButLeavesForeignChannelsAlone() throws Exception {
        ProxiedPlayer player = player(UUID.randomUUID(), new ArrayList<>());
        ProxyServer proxy = proxy(new AtomicReference<>(player));
        var manager = BungeeNetworkManager.getInstance(); manager.initialize(proxy, (p, b) -> TestProxy.ready());
        var initializer = new BungeeEventListener(proxy, java.util.logging.Logger.getLogger("test"), manager);
        Server backend = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                new Class<?>[]{Server.class}, (p, m, a) -> { throw new AssertionError(m.getName()); });
        try {
            var owned = new PluginMessageEvent(backend, player,
                    "musichud_tuneweave:switch_music_message", new byte[0]);
            initializer.onPluginMessage(owned);
            assertTrue(owned.isCancelled());
            var foreign = new PluginMessageEvent(backend, player, "other:payload", new byte[0]);
            initializer.onPluginMessage(foreign);
            assertFalse(foreign.isCancelled());
        } finally { manager.close(); }
    }

    @Test void actualReceiverRejectsMalformedOversizedAndRetiredTraffic() {
        var manager = BungeeNetworkManager.getInstance();
        ProxiedPlayer player = player(UUID.randomUUID(), new ArrayList<>());
        var proxy = new TestProxy(new AtomicReference<>(player));
        var receives = new java.util.concurrent.atomic.AtomicInteger();
        manager.initialize(proxy, (p, b) -> TestProxy.ready());
        try {
            manager.registerC2SPayload(indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest.class,
                    indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest.CODEC,
                    (payload, peer) -> receives.incrementAndGet());
            String channel = ProtocolChannels.id(indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest.class);
            var buffer = Unpooled.buffer();
            byte[] valid;
            try {
                indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest.CODEC.encode(buffer,
                        indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest.current());
                valid = new byte[buffer.readableBytes()]; buffer.readBytes(valid);
            } finally { buffer.release(); }
            manager.handle(player, channel, new byte[0]);
            manager.handle(player, channel, Arrays.copyOf(valid, valid.length + 1));
            manager.handle(player, channel, new byte[1_048_577]);
            assertEquals(0, receives.get());
            manager.handle(player, channel, valid);
            assertEquals(1, receives.get());
            BungeePlayerProxy.disconnect(player);
            manager.handle(player, channel, valid);
            assertEquals(1, receives.get());
        } finally { manager.close(); }
        assertTrue(proxy.channels.isEmpty());
    }

    @Test void actualPlaybackCodecWaitsForPlayAndDiscardsOldBackendQueue() {
        var manager = BungeeNetworkManager.getInstance();
        var sent = new ArrayList<byte[]>();
        var backend = new AtomicReference<Server>(TestProxy.BACKEND);
        var peer = player(UUID.randomUUID(), sent, backend);
        var gate = new ManualTransport();
        manager.initialize(proxy(new AtomicReference<>(peer)), (p, b) -> gate);
        try {
            manager.registerS2CPayload(indi.mopelotus.musichud.network.payloads.pushMessages.s2c.SyncCurrentPlayingMessage.class,
                    indi.mopelotus.musichud.network.payloads.pushMessages.s2c.SyncCurrentPlayingMessage.CODEC, NetworkReceiver.noop());
            var old = new indi.mopelotus.musichud.network.payloads.pushMessages.s2c.SyncCurrentPlayingMessage(
                    indi.mopelotus.musichud.beans.music.PlaybackSession.stopped(17),
                    indi.mopelotus.musichud.beans.music.MusicDetail.NONE);
            manager.sendToPlayer(BungeePlayerProxy.of(peer), old);
            gate.tick();
            assertTrue(sent.isEmpty(), "CONFIGURATION must not receive playback packets");
            Server replacement = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                    (p, m, a) -> { if (m.getName().equals("isConnected")) return true; throw new AssertionError(m.getName()); });
            backend.set(replacement);
            var latest = new indi.mopelotus.musichud.network.payloads.pushMessages.s2c.SyncCurrentPlayingMessage(
                    indi.mopelotus.musichud.beans.music.PlaybackSession.stopped(18),
                    indi.mopelotus.musichud.beans.music.MusicDetail.NONE);
            manager.afterBackendReady(peer, 0, () -> manager.sendToPlayer(BungeePlayerProxy.of(peer), latest));
            gate.play = true;
            gate.tick();
            gate.tick();
            assertEquals(1, sent.size(), "old backend must not flush after a later switch");
            var bytes = Unpooled.wrappedBuffer(sent.getFirst());
            try {
                var decoded = indi.mopelotus.musichud.network.payloads.pushMessages.s2c.SyncCurrentPlayingMessage.CODEC.decode(bytes);
                assertEquals(18, decoded.playbackSession().sequence());
                assertEquals(latest.playbackSession().sessionId(), decoded.playbackSession().sessionId());
                assertEquals(latest.playbackSession().startTime(), decoded.playbackSession().startTime());
                assertFalse(decoded.playbackSession().isActive());
                assertFalse(bytes.isReadable());
            } finally { bytes.release(); }
        } finally { manager.close(); }
    }

    @Test void closeThenReinitializeDoesNotFlushPreviousPhysicalConnection() {
        var manager = BungeeNetworkManager.getInstance();
        var sent = new ArrayList<byte[]>();
        var peer = player(UUID.randomUUID(), sent);
        var gate = new ManualTransport();
        var proxy = proxy(new AtomicReference<>(peer));
        manager.initialize(proxy, (p, b) -> gate);
        try {
            manager.registerS2CPayload(LargePayload.class, ByteBufCodec.composite(Codecs.STRING_UTF8, LargePayload::value, LargePayload::new), NetworkReceiver.noop());
            manager.sendToPlayer(BungeePlayerProxy.of(peer), new LargePayload("before shutdown"));
            gate.tick();
            manager.close();
            manager.initialize(proxy, (p, b) -> TestProxy.ready());
            gate.play = true;
            gate.tick();
            assertTrue(sent.isEmpty());
        } finally { manager.close(); }
    }

    private static final class ManualTransport implements BungeePlayGate.Transport {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean play;
        public boolean ready() { return play; }
        public void execute(Runnable task) { tasks.add(task); }
        public void later(Runnable task) { tasks.add(task); }
        void tick() { for (int count = tasks.size(); count > 0; count--) tasks.removeFirst().run(); }
    }

    private static ProxiedPlayer player(UUID id, List<byte[]> sent) {
        return player(id, sent, new AtomicReference<>(TestProxy.BACKEND));
    }

    private static ProxiedPlayer player(UUID id, List<byte[]> sent, AtomicReference<Server> backend) {
        return (ProxiedPlayer) Proxy.newProxyInstance(ProxiedPlayer.class.getClassLoader(), new Class<?>[]{ProxiedPlayer.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "ProxiedPlayer";
            case "isConnected" -> true;
            case "getServer" -> backend.get();
            case "sendData" -> { sent.add(((byte[]) args[1]).clone()); yield null; }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "ProxiedPlayer";
            default -> throw new AssertionError(method.getName());
        });
    }

    private static ProxyServer proxy(AtomicReference<ProxiedPlayer> active) {
        return new TestProxy(active);
    }

    private record LargePayload(String value) implements S2CPayload {}
    private record BinaryPayload(byte[] value) implements S2CPayload {}
    private static final ByteBufCodec<BinaryPayload> BINARY_CODEC = new ByteBufCodec<>() {
        public void encode(io.netty.buffer.ByteBuf buffer, BinaryPayload payload) { buffer.writeBytes(payload.value()); }
        public BinaryPayload decode(io.netty.buffer.ByteBuf buffer) {
            byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return new BinaryPayload(bytes);
        }
    };
}
