package indi.mopelotus.musichud.bungeecord;

import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.*;

import net.md_5.bungee.api.config.ServerInfo;
import indi.mopelotus.musichud.network.*;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.RefreshMusicQueueMessage;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest;
import indi.mopelotus.musichud.platform.plugin.bungeecord.deployment.BungeeDeploymentGuard;
import indi.mopelotus.musichud.platform.plugin.bungeecord.network.*;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import indi.mopelotus.musichud.utils.ServerConnectionControlQueue;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BungeeDeploymentGuardTest {
    @TempDir Path directory;
    private static final String CHANNEL = ProtocolChannels.id(RefreshMusicQueueMessage.class);

    @Test void backendAuthorityClosesMembershipAndAlreadyQueuedHandshake() throws Exception {
        try (var h = new Harness(directory)) {
            var client = BungeePlayerProxy.of(h.player);
            h.registry.join(client);
            var queuedBusiness = ServerPacketAdmission.capture(new C2SPayload() {}, client);
            List<Runnable> work = new ArrayList<>();
            var queue = new ServerConnectionControlQueue(work::add);
            var handshake = ServerDataPacketVThreadExecutor.<ConnectRequest>controlReceiver(queue,
                    (payload, peer) -> h.registry.join(peer));
            handshake.receive(ConnectRequest.current(), client);
            assertEquals(1, work.size());

            var event = h.event(h.backend, CHANNEL);
            h.initializer.onPluginMessage(event);
            assertTrue(event.isCancelled());
            assertEquals(1, h.kicks.get());
            assertFalse(client.isConnected());
            assertFalse(h.registry.contains(h.id));
            assertFalse(queuedBusiness.allowed());
            assertFalse(ServerPacketAdmission.capture(ConnectRequest.current(), client).allowed());
            work.forEach(Runnable::run);
            h.registry.join(client);
            assertFalse(h.registry.contains(h.id));
            h.network.sendToPlayer(client, new RefreshMusicQueueMessage(new ArrayDeque<>()));
            assertEquals(0, h.sends.get());

            h.initializer.onPluginMessage(h.event(h.backend, CHANNEL));
            assertEquals(1, h.kicks.get(), "Repeated backend packets must not repeat the disconnect");
            ProxiedPlayer freshLogin = h.newPlayer();
            h.active.set(freshLogin);
            h.registry.join(BungeePlayerProxy.of(freshLogin));
            assertTrue(h.registry.contains(h.id), "A fresh login with the same UUID is independent");
        }
    }

    @Test void replacedPhysicalConnectionCannotRunItsQueuedHandshake() throws Exception {
        try (var h = new Harness(directory)) {
            var old = BungeePlayerProxy.of(h.player);
            List<Runnable> work = new ArrayList<>();
            var queue = new ServerConnectionControlQueue(work::add);
            var receiver = ServerDataPacketVThreadExecutor.<ConnectRequest>controlReceiver(queue,
                    (payload, peer) -> h.registry.join(peer));
            receiver.receive(ConnectRequest.current(), old);
            assertEquals(1, work.size());
            var replacement = h.newPlayer();
            h.active.set(replacement);
            h.registry.join(BungeePlayerProxy.of(replacement));
            assertFalse(old.isConnected(), "The old native connection may still report active while replacement is current");
            work.forEach(Runnable::run);
            assertSame(replacement, ((IPlayerClient) h.registry.sessionToken(h.id)).connectionIdentity());
        }
    }

    @Test void clientSpoofAndUnknownChannelDoNotTriggerADeploymentConflict() throws Exception {
        try (var h = new Harness(directory)) {
            h.registry.join(BungeePlayerProxy.of(h.player));
            h.initializer.onPluginMessage(h.event(h.player, CHANNEL));
            h.initializer.onPluginMessage(h.event(h.backend, "musichud_tuneweave:unknown"));
            var foreign = h.event(h.backend, "other:payload");
            h.initializer.onPluginMessage(foreign);
            assertFalse(foreign.isCancelled());
            assertEquals(0, h.kicks.get());
            assertTrue(h.registry.contains(h.id));
        }
    }

    @Test void lateOldBackendOrOldLoginCannotCloseTheCurrentPeer() throws Exception {
        try (var h = new Harness(directory)) {
            var client = BungeePlayerProxy.of(h.player);
            h.registry.join(client);
            h.current.set(h.newBackend(h.player, "new-backend"));
            h.initializer.onPluginMessage(h.event(h.backend, CHANNEL));
            assertEquals(0, h.kicks.get());
            assertTrue(h.registry.contains(h.id));
            h.current.set(h.backend);
            ProxiedPlayer freshLogin = h.newPlayer();
            h.active.set(freshLogin);
            h.registry.join(BungeePlayerProxy.of(freshLogin));
            h.initializer.onPluginMessage(h.event(h.backend, CHANNEL));
            assertEquals(0, h.kicks.get());
            assertSame(freshLogin, ((IPlayerClient) h.registry.sessionToken(h.id)).connectionIdentity());
        }
    }

    @Test void reportingFailureStillDisconnectsTheAffectedPhysicalConnection() throws Exception {
        try (var h = new Harness(directory)) {
            h.registry.join(BungeePlayerProxy.of(h.player));
            assertThrows(IllegalStateException.class, () -> BungeeDeploymentGuard.rejectBackendAuthority(
                    h.proxy, h.network, h.player, h.backend, CHANNEL, new byte[0], (server, channel) -> {
                        throw new IllegalStateException("reporter failed");
                    }));
            assertEquals(1, h.kicks.get());
            assertFalse(h.registry.contains(h.id));
            assertFalse(BungeePlayerProxy.of(h.player).isConnected());
        }
    }

    @Test void presenceProbeOnlyAcceptsCurrentBackendAndNeverClientClaims() throws Exception {
        try (var h = new Harness(directory)) {
            var probe = ProxyDeploymentProbe.CHANNEL;
            for (byte[] invalid : new byte[][]{new byte[0], ProxyDeploymentProbe.request(), new byte[]{77,84,87,2,1}}) {
                h.initializer.onPluginMessage(new PluginMessageEvent(h.backend, h.player, probe, invalid));
            }
            h.initializer.onPluginMessage(new PluginMessageEvent(h.player, h.player, probe, ProxyDeploymentProbe.present()));
            assertEquals(0, h.kicks.get());
            h.current.set(h.newBackend(h.player, "fresh"));
            h.initializer.onPluginMessage(new PluginMessageEvent(h.backend, h.player, probe, ProxyDeploymentProbe.present()));
            assertEquals(0, h.kicks.get());
            h.current.set(h.backend);
            h.initializer.onPluginMessage(new PluginMessageEvent(h.backend, h.player, probe, ProxyDeploymentProbe.present()));
            assertEquals(1, h.kicks.get());
            assertFalse(BungeePlayerProxy.of(h.player).isConnected());
        }
    }

    @Test void presenceArrivingAfterNetworkShutdownCannotDisconnectThePeer() throws Exception {
        try (var h = new Harness(directory)) {
            h.network.close();
            h.initializer.onPluginMessage(new PluginMessageEvent(h.backend, h.player,
                    ProxyDeploymentProbe.CHANNEL, ProxyDeploymentProbe.present()));
            assertEquals(0, h.kicks.get());
        }
    }

    @Test void probesRunBeforeMembershipAndStopAfterDisconnectOrShutdown() throws Exception {
        try (var h = new Harness(directory)) {
            h.initializer.onServerChanged(new net.md_5.bungee.api.event.ServerSwitchEvent(h.player, null));
            assertEquals(1, h.probes.get());
            h.current.set(h.newBackend(h.player, "second"));
            h.initializer.onServerChanged(new net.md_5.bungee.api.event.ServerSwitchEvent(h.player, null));
            assertEquals(2, h.probes.get());
            BungeePlayerProxy.disconnect(h.player);
            h.network.probeBackend(h.player);
            assertEquals(2, h.probes.get());
            h.network.close();
            h.network.probeBackend(h.player);
            assertEquals(2, h.probes.get());
        }
    }

    private static final class Harness implements AutoCloseable {
        final UUID id = UUID.randomUUID();
        final AtomicInteger kicks = new AtomicInteger(), sends = new AtomicInteger(), probes = new AtomicInteger();
        final AtomicReference<Server> current = new AtomicReference<>();
        final ProxiedPlayer player = newPlayer();
        final AtomicReference<ProxiedPlayer> active = new AtomicReference<>(player);
        final Server backend = newBackend(player, "backend");
        final BungeeNetworkManager network = BungeeNetworkManager.getInstance();
        final ServerPlayerRegistry registry = ServerPlayerRegistry.getInstance();
        final ProxyServer proxy;
        final BungeeEventListener initializer;

        Harness(Path directory) throws Exception {
            current.set(backend);
            proxy = new TestProxy(active);
            network.initialize(proxy, (player, backend) -> TestProxy.ready());
            network.registerS2CPayload(RefreshMusicQueueMessage.class, RefreshMusicQueueMessage.CODEC, NetworkReceiver.noop());
            initializer = new BungeeEventListener(proxy, java.util.logging.Logger.getLogger("deployment-test"), network);
        }

        ProxiedPlayer newPlayer() {
            return (ProxiedPlayer) Proxy.newProxyInstance(ProxiedPlayer.class.getClassLoader(), new Class<?>[]{ProxiedPlayer.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> "DeploymentTest";
                        case "isConnected" -> true;
                        case "getServer" -> current.get();
                        case "disconnect" -> { kicks.incrementAndGet(); yield null; }
                        case "sendData" -> { sends.incrementAndGet(); yield null; }
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        case "toString" -> "DeploymentTest";
                        default -> throw new AssertionError(method.getName());
                    });
        }

        Server newBackend(ProxiedPlayer owner, String name) {
            return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                    new Class<?>[]{Server.class}, (p, method, args) -> switch (method.getName()) {
                        case "getPlayer" -> owner;
                        case "isConnected" -> true;
                        case "sendData" -> {
                            assertEquals(ProxyDeploymentProbe.CHANNEL, args[0]);
                            assertTrue(ProxyDeploymentProbe.isRequest((byte[]) args[1]));
                            probes.incrementAndGet(); yield null;
                        }
                        case "getInfo" -> Proxy.newProxyInstance(ServerInfo.class.getClassLoader(), new Class<?>[]{ServerInfo.class},
                                (info, m, a) -> { if (m.getName().equals("getName")) return name; throw new AssertionError(m.getName()); });
                        default -> throw new AssertionError(method.getName());
                    });
        }

        PluginMessageEvent event(Connection source, String channel) {
            return new PluginMessageEvent(source, player, channel, new byte[0]);
        }
        @Override public void close() { registry.clear(); network.close(); }
    }
}
