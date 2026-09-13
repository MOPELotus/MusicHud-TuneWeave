package indi.mopelotus.musichud.velocity;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.*;
import com.velocitypowered.api.proxy.server.ServerInfo;
import indi.mopelotus.musichud.network.*;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.RefreshMusicQueueMessage;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest;
import indi.mopelotus.musichud.platform.plugin.velocity.deployment.VelocityDeploymentGuard;
import indi.mopelotus.musichud.platform.plugin.velocity.network.*;
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

class VelocityDeploymentGuardTest {
    @TempDir Path directory;
    private static final String CHANNEL = ProtocolChannels.id(RefreshMusicQueueMessage.class);

    @Test void backendAuthorityClosesMembershipAndAlreadyQueuedHandshake() throws Exception {
        try (var h = new Harness(directory)) {
            var client = VelocityPlayerProxy.of(h.player);
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
            assertEquals(PluginMessageEvent.ForwardResult.handled(), event.getResult());
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
            Player freshLogin = h.newPlayer();
            h.active.set(freshLogin);
            h.registry.join(VelocityPlayerProxy.of(freshLogin));
            assertTrue(h.registry.contains(h.id), "A fresh login with the same UUID is independent");
        }
    }

    @Test void replacedPhysicalConnectionCannotRunItsQueuedHandshake() throws Exception {
        try (var h = new Harness(directory)) {
            var old = VelocityPlayerProxy.of(h.player);
            List<Runnable> work = new ArrayList<>();
            var queue = new ServerConnectionControlQueue(work::add);
            var receiver = ServerDataPacketVThreadExecutor.<ConnectRequest>controlReceiver(queue,
                    (payload, peer) -> h.registry.join(peer));
            receiver.receive(ConnectRequest.current(), old);
            assertEquals(1, work.size());
            var replacement = h.newPlayer();
            h.active.set(replacement);
            h.registry.join(VelocityPlayerProxy.of(replacement));
            assertFalse(old.isConnected(), "The old native connection may still report active while replacement is current");
            work.forEach(Runnable::run);
            assertSame(replacement, ((IPlayerClient) h.registry.sessionToken(h.id)).connectionIdentity());
        }
    }

    @Test void clientSpoofAndUnknownChannelDoNotTriggerADeploymentConflict() throws Exception {
        try (var h = new Harness(directory)) {
            h.registry.join(VelocityPlayerProxy.of(h.player));
            h.initializer.onPluginMessage(h.event(h.player, CHANNEL));
            h.initializer.onPluginMessage(h.event(h.backend, "musichud_tuneweave:unknown"));
            var foreign = h.event(h.backend, "other:payload");
            h.initializer.onPluginMessage(foreign);
            assertEquals(PluginMessageEvent.ForwardResult.forward(), foreign.getResult());
            assertEquals(0, h.kicks.get());
            assertTrue(h.registry.contains(h.id));
        }
    }

    @Test void lateOldBackendOrOldLoginCannotCloseTheCurrentPeer() throws Exception {
        try (var h = new Harness(directory)) {
            var client = VelocityPlayerProxy.of(h.player);
            h.registry.join(client);
            h.current.set(h.newBackend(h.player, "new-backend"));
            h.initializer.onPluginMessage(h.event(h.backend, CHANNEL));
            assertEquals(0, h.kicks.get());
            assertTrue(h.registry.contains(h.id));
            h.current.set(h.backend);
            Player freshLogin = h.newPlayer();
            h.active.set(freshLogin);
            h.registry.join(VelocityPlayerProxy.of(freshLogin));
            h.initializer.onPluginMessage(h.event(h.backend, CHANNEL));
            assertEquals(0, h.kicks.get());
            assertSame(freshLogin, ((IPlayerClient) h.registry.sessionToken(h.id)).connectionIdentity());
        }
    }

    @Test void reportingFailureStillDisconnectsTheAffectedPhysicalConnection() throws Exception {
        try (var h = new Harness(directory)) {
            h.registry.join(VelocityPlayerProxy.of(h.player));
            assertThrows(IllegalStateException.class, () -> VelocityDeploymentGuard.rejectBackendAuthority(
                    h.proxy, h.network, h.backend, CHANNEL, new byte[0], (server, channel) -> {
                        throw new IllegalStateException("reporter failed");
                    }));
            assertEquals(1, h.kicks.get());
            assertFalse(h.registry.contains(h.id));
            assertFalse(VelocityPlayerProxy.of(h.player).isConnected());
        }
    }

    @Test void presenceProbeOnlyAcceptsCurrentBackendAndNeverClientClaims() throws Exception {
        try (var h = new Harness(directory)) {
            var probe = MinecraftChannelIdentifier.from(ProxyDeploymentProbe.CHANNEL);
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
            assertFalse(VelocityPlayerProxy.of(h.player).isConnected());
        }
    }

    @Test void presenceArrivingAfterNetworkShutdownCannotDisconnectThePeer() throws Exception {
        try (var h = new Harness(directory)) {
            h.network.close();
            h.initializer.onPluginMessage(new PluginMessageEvent(h.backend, h.player,
                    MinecraftChannelIdentifier.from(ProxyDeploymentProbe.CHANNEL), ProxyDeploymentProbe.present()));
            assertEquals(0, h.kicks.get());
        }
    }

    @Test void probesRunBeforeMembershipAndStopAfterDisconnectOrShutdown() throws Exception {
        try (var h = new Harness(directory)) {
            h.initializer.onServerChanged(new com.velocitypowered.api.event.player.ServerPostConnectEvent(h.player, null));
            assertEquals(1, h.probes.get());
            h.current.set(h.newBackend(h.player, "second"));
            h.initializer.onServerChanged(new com.velocitypowered.api.event.player.ServerPostConnectEvent(h.player, null));
            assertEquals(2, h.probes.get());
            VelocityPlayerProxy.disconnect(h.player);
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
        final AtomicReference<ServerConnection> current = new AtomicReference<>();
        final Player player = newPlayer();
        final AtomicReference<Player> active = new AtomicReference<>(player);
        final ServerConnection backend = newBackend(player, "backend");
        final VelocityNetworkManager network = VelocityNetworkManager.getInstance();
        final ServerPlayerRegistry registry = ServerPlayerRegistry.getInstance();
        final ProxyServer proxy;
        final VelocityInitializer initializer;

        Harness(Path directory) throws Exception {
            current.set(backend);
            var registrar = (ChannelRegistrar) Proxy.newProxyInstance(ChannelRegistrar.class.getClassLoader(),
                    new Class<?>[]{ChannelRegistrar.class}, (p, method, args) -> null);
            proxy = (ProxyServer) Proxy.newProxyInstance(ProxyServer.class.getClassLoader(),
                    new Class<?>[]{ProxyServer.class}, (p, method, args) -> switch (method.getName()) {
                        case "getPlayer" -> Optional.ofNullable(active.get());
                        case "getChannelRegistrar" -> registrar;
                        default -> throw new AssertionError(method.getName());
                    });
            network.initialize(proxy);
            network.registerS2CPayload(RefreshMusicQueueMessage.class, RefreshMusicQueueMessage.CODEC, NetworkReceiver.noop());
            initializer = new VelocityInitializer(proxy, org.slf4j.LoggerFactory.getLogger("deployment-test"), directory);
            var field = VelocityInitializer.class.getDeclaredField("networkManager");
            field.setAccessible(true); field.set(initializer, network);
        }

        Player newPlayer() {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getUsername" -> "DeploymentTest";
                        case "isActive" -> true;
                        case "getCurrentServer" -> Optional.ofNullable(current.get());
                        case "disconnect" -> { kicks.incrementAndGet(); yield null; }
                        case "sendPluginMessage" -> { sends.incrementAndGet(); yield true; }
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        case "toString" -> "DeploymentTest";
                        default -> throw new AssertionError(method.getName());
                    });
        }

        ServerConnection newBackend(Player owner, String name) {
            return (ServerConnection) Proxy.newProxyInstance(ServerConnection.class.getClassLoader(),
                    new Class<?>[]{ServerConnection.class}, (p, method, args) -> switch (method.getName()) {
                        case "getPlayer" -> owner;
                        case "sendPluginMessage" -> {
                            assertEquals(ProxyDeploymentProbe.CHANNEL, ((ChannelIdentifier) args[0]).getId());
                            assertTrue(ProxyDeploymentProbe.isRequest((byte[]) args[1]));
                            probes.incrementAndGet(); yield true;
                        }
                        case "getServerInfo" -> new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25566));
                        default -> throw new AssertionError(method.getName());
                    });
        }

        PluginMessageEvent event(com.velocitypowered.api.proxy.messages.ChannelMessageSource source, String channel) {
            return new PluginMessageEvent(source, player, MinecraftChannelIdentifier.from(channel), new byte[0]);
        }
        @Override public void close() { registry.clear(); network.close(); }
    }
}
