package indi.mopelotus.musichud.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class MusicHudMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        // Dedicated servers have no UI host. Client validation runs before target transformation.
        if (getClass().getClassLoader().getResource("icyllis/modernui/mc/UIManager.class") != null) {
            indi.mopelotus.musichud.client.compat.ModernUiHost.isLumen();
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains("SoundEngineDynamicFpsCompatMixin")) {
            try {
                Class.forName("dynamic_fps.impl.DynamicFPSMod", false, getClass().getClassLoader());
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
        if (mixinClassName.endsWith(".LumenGraphicsLifecycleMixin")) {
            return indi.mopelotus.musichud.client.compat.ModernUiHost.isLumen();
        }
        if (mixinClassName.endsWith(".ModernUiGraphicsLifecycleMixin")) {
            return !indi.mopelotus.musichud.client.compat.ModernUiHost.isLumen();
        }
        if (mixinClassName.endsWith(".DynamicFpsCompanionMixin")) {
            return getClass().getClassLoader().getResource("dynamic_fps/impl/DynamicFPSMod.class") != null;
        }
        if (mixinClassName.contains("ReactiveMusicCompatMixin")) {
            try {
                Class.forName("circuitlord.reactivemusic.ReactiveMusic", false, getClass().getClassLoader());
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
