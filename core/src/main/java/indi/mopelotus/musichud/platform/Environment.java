package indi.mopelotus.musichud.platform;

import indi.mopelotus.musichud.interfaces.*;
import indi.mopelotus.musichud.network.IClientNetworkService;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.IServerNetworkService;
import indi.mopelotus.musichud.utils.IClientDistUtil;
import lombok.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Supplier;

@Getter
@Setter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Environment {
    private Side side;
    private Platform platform;

    public static Environment of(Side side, Platform platform) {
        return new Environment(side, platform);
    }

    public enum Side {
        CLIENT,
        SERVER
    }

    @Getter
    @AllArgsConstructor
    public enum Platform {
        FABRIC(
                () -> load("indi.mopelotus.musichud.platform.mod.config.ServerConfigDefinition", ServerConfig.class),
                () -> load("indi.mopelotus.musichud.platform.mod.fabric.network.FabricNetworkRegister", INetworkRegister.class),
                () -> load("indi.mopelotus.musichud.platform.mod.fabric.network.FabricServerNetworkService", IServerNetworkService.class),
                () -> load("indi.mopelotus.musichud.platform.mod.fabric.event.FabricCommonEventService", ICommonEventService.class),
                () -> load("indi.mopelotus.musichud.platform.mod.config.ClientConfigDefinition", ClientConfig.class),
                () -> load("indi.mopelotus.musichud.platform.mod.fabric.network.FabricClientNetworkService", IClientNetworkService.class),
                () -> load("indi.mopelotus.musichud.platform.mod.fabric.event.FabricClientEventService", IClientEventServiceDefinition.class),
                () -> load("indi.mopelotus.musichud.platform.mod.fabric.registry.FabricKeyRegistryService", IKeyRegistryServiceDefinition.class),
                () -> load("indi.mopelotus.musichud.client.utils.ClientDistUtil", IClientDistUtil.class),
                () -> load("indi.mopelotus.musichud.client.services.LoginService", IClientLoginService.class),
                () -> load("indi.mopelotus.musichud.client.services.music.MusicService", IClientMusicService.class),
                () -> load("indi.mopelotus.musichud.client.services.ConnectionManager", IConnectionManager.class)),
        NEOFORGE(
                () -> load("indi.mopelotus.musichud.platform.mod.config.ServerConfigDefinition", ServerConfig.class),
                () -> load("indi.mopelotus.musichud.platform.mod.neoforge.network.NeoForgeNetworkManager", INetworkRegister.class),
                () -> load("indi.mopelotus.musichud.platform.mod.neoforge.network.NeoForgeNetworkManager", IServerNetworkService.class),
                () -> load("indi.mopelotus.musichud.platform.mod.neoforge.event.NeoForgeCommonEventService", ICommonEventService.class),
                () -> load("indi.mopelotus.musichud.platform.mod.config.ClientConfigDefinition", ClientConfig.class),
                () -> load("indi.mopelotus.musichud.platform.mod.neoforge.network.NeoForgeClientNetworkService", IClientNetworkService.class),
                () -> load("indi.mopelotus.musichud.platform.mod.neoforge.event.NeoForgeClientEventService", IClientEventServiceDefinition.class),
                () -> load("indi.mopelotus.musichud.platform.mod.neoforge.registry.NeoForgeKeyRegistryService", IKeyRegistryServiceDefinition.class),
                () -> load("indi.mopelotus.musichud.client.utils.ClientDistUtil", IClientDistUtil.class),
                () -> load("indi.mopelotus.musichud.client.services.LoginService", IClientLoginService.class),
                () -> load("indi.mopelotus.musichud.client.services.music.MusicService", IClientMusicService.class),
                () -> load("indi.mopelotus.musichud.client.services.ConnectionManager", IConnectionManager.class)),
        VELOCITY(
                () -> load("indi.mopelotus.musichud.platform.plugin.velocity.config.VelocityServerConfig", ServerConfig.class),
                () -> load("indi.mopelotus.musichud.platform.plugin.velocity.network.VelocityNetworkManager", INetworkRegister.class),
                () -> load("indi.mopelotus.musichud.platform.plugin.velocity.network.VelocityNetworkManager", IServerNetworkService.class),
                () -> load("indi.mopelotus.musichud.platform.plugin.velocity.event.VelocityEventService", ICommonEventService.class),
                null, null, null, null, null, null, null, null),
        PAPER(
                () -> load("indi.mopelotus.musichud.platform.plugin.paper.config.ServerConfigDefinition", ServerConfig.class),
                () -> load("indi.mopelotus.musichud.platform.plugin.paper.network.PaperNetworkManager", INetworkRegister.class),
                () -> load("indi.mopelotus.musichud.platform.plugin.paper.network.PaperNetworkManager", IServerNetworkService.class),
                () -> load("indi.mopelotus.musichud.platform.plugin.paper.event.PaperEventService", ICommonEventService.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        private final Supplier<ServerConfig> serverConfigSupplier;
        private final Supplier<INetworkRegister> networkRegisterSupplier;
        private final Supplier<IServerNetworkService> serverNetworkServiceSupplier;
        private final Supplier<ICommonEventService> serverEventServiceSupplier;
        private final Supplier<ClientConfig> clientConfigSupplier;
        private final Supplier<IClientNetworkService> clientNetworkServiceSupplier;
        private final Supplier<IClientEventServiceDefinition> clientEventServiceSupplier;
        private final Supplier<IKeyRegistryServiceDefinition> keyRegistryServiceSupplier;
        private final Supplier<IClientDistUtil> clientDistUtilSupplier;
        private final Supplier<IClientLoginService> clientLoginServiceSupplier;
        private final Supplier<IClientMusicService> clientMusicServiceSupplier;
        private final Supplier<IConnectionManager> connectionManagerSupplier;

        @SneakyThrows
        static <T> T load(String className, Class<T> expectedType) {
            Class<?> clazz = Class.forName(className);
            Object instance = MethodHandles.lookup()
                    .findStatic(clazz, "getInstance", MethodType.methodType(clazz))
                    .invoke();
            return expectedType.cast(instance);
        }
    }

    @Override
    public String toString() {
        return "Environment{" + platform.name() + "-" + side.name() + "}";
    }
}
