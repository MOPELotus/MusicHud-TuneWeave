package indi.mopelotus.musichud.platform.plugin.bungeecord.network;

import io.netty.channel.Channel;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import net.md_5.bungee.protocol.Protocol;

/** Narrow bridge to the pinned Bungee core: its public plugin API has no PLAY-ready event or query. */
public final class BungeeNativeTransport implements BungeePlayGate.Transport {
    private static final ClassValue<Methods> METHODS = new ClassValue<>() {
        @Override protected Methods computeValue(Class<?> type) {
            try {
                Method wrapper = type.getMethod("getCh");
                Class<?> channelType = wrapper.getReturnType();
                return new Methods(wrapper, channelType.getMethod("getEncodeProtocol"), channelType.getMethod("getHandle"));
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Unsupported BungeeCord core: cannot verify the connection's PLAY phase", error);
            }
        }
    };
    private final Object playerWrapper, backendWrapper;
    private final Methods playerMethods, backendMethods;
    private final Channel channel;

    public BungeeNativeTransport(Object player, Object backend) {
        playerMethods = METHODS.get(player.getClass());
        backendMethods = METHODS.get(backend.getClass());
        playerWrapper = invoke(playerMethods.wrapper, player);
        backendWrapper = invoke(backendMethods.wrapper, backend);
        channel = (Channel) invoke(backendMethods.handle, backendWrapper);
    }
    public static void verifyRuntime(ClassLoader loader) {
        try {
            METHODS.get(Class.forName("net.md_5.bungee.UserConnection", false, loader));
            METHODS.get(Class.forName("net.md_5.bungee.ServerConnection", false, loader));
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("Unsupported BungeeCord core: native connection types are missing", error);
        }
    }
    @Override public boolean ready() {
        return invoke(playerMethods.phase, playerWrapper) == Protocol.GAME
                && invoke(backendMethods.phase, backendWrapper) == Protocol.GAME;
    }
    @Override public void execute(Runnable task) {
        if (channel.eventLoop().inEventLoop()) task.run();
        else channel.eventLoop().execute(task);
    }
    @Override public void later(Runnable task) { channel.eventLoop().schedule(task, 100, TimeUnit.MILLISECONDS); }
    private static Object invoke(Method method, Object owner) {
        try { return method.invoke(owner); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot inspect Bungee connection phase", error); }
    }
    private record Methods(Method wrapper, Method phase, Method handle) {}
}
