package indi.mopelotus.musichud.fabric;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

/** The UI host is required on clients, while dedicated servers do not load a UI host. */
public final class FabricClientDependencies implements net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT) {
            var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            var requirement = loader.getModContainer(indi.mopelotus.musichud.MusicHud.MOD_ID).orElseThrow().getMetadata()
                    .getCustomValue("musichud_tuneweave_client_modernui").getAsString();
            try {
                FabricClientDependencies.requireModernUi(true,
                        loader.getModContainer("modernui").map(mod -> mod.getMetadata().getVersion()).orElse(null),
                        FabricClientDependencies.parseModernUiRequirement(requirement));
            } catch (net.fabricmc.loader.api.VersionParsingException malformed) {
                throw new IllegalStateException("Invalid packaged ModernUI client requirement", malformed);
            }
        }
    }

    static VersionPredicate parseModernUiRequirement(String constraint)
            throws net.fabricmc.loader.api.VersionParsingException {
        if (constraint == null || !constraint.startsWith(">=")) {
            throw new net.fabricmc.loader.api.VersionParsingException("Invalid ModernUI client constraint");
        }
        net.fabricmc.loader.api.SemanticVersion.parse(constraint.substring(2));
        return VersionPredicate.parse(constraint);
    }

    static void requireModernUi(boolean client, Version installed, VersionPredicate required) {
        if (!client) return;
        if (installed == null || !required.test(installed)) {
            String problem = installed == null
                    ? "未安装 ModernUI / ModernUI is missing"
                    : "ModernUI 版本过低 / ModernUI version is too old";
            throw new IllegalStateException(problem + "\n"
                    + "需要 / Required: ModernUI " + required + "\n"
                    + "当前安装 / Installed: " + (installed == null ? "未安装 / missing" : installed.getFriendlyString()) + "\n"
                    + "请使用适配当前 Minecraft 版本的新版 ModernUI。\n"
                    + "Please use an updated ModernUI build for your Minecraft version.\n"
                    + "下载 / Downloads: https://github.com/MOPELotus/ModernUI-MC/releases");
        }
    }
}
