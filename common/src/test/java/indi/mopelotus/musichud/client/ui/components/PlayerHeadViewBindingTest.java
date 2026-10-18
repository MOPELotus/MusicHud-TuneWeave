package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real view binding with no world and no graphics uploads. */
class PlayerHeadViewBindingTest {
    @TempDir Path fixtures;
    @Test void temporaryNullPreservesSkinButClearAndDifferentBindingCannotReuseIt() throws Exception {
        var extras = Map.of(
                "net.minecraft.resources.ResourceLocation", "public record ResourceLocation(String value) {}",
                "net.minecraft.client.Minecraft", "public class Minecraft { static final Minecraft INSTANCE=new Minecraft(); public net.minecraft.client.multiplayer.ClientLevel level; public static Minecraft getInstance(){ return INSTANCE; } }",
                "indi.mopelotus.musichud.client.utils.image.ClientGraphicsResources", "public class ClientGraphicsResources { public static final indi.mopelotus.musichud.client.utils.OwnedResources RENDER=new indi.mopelotus.musichud.client.utils.OwnedResources(e -> {}); public static void releaseUiResource(AutoCloseable r) {} public static void releaseImage(icyllis.modernui.graphics.Image i) {} }");
        try (var e = new StaggeredLyricLifecycleTest.Environment(fixtures, new LayoutTestContext(), extras)) {
            Class<?> type = e.productionClass("indi.mopelotus.musichud.client.ui.components.PlayerHeadView");
            Object view = type.getConstructor(Context.class).newInstance(new LayoutTestContext());
            Object first = e.loadClass("net.minecraft.resources.ResourceLocation").getConstructor(String.class).newInstance("first");
            AtomicReference<Object> result = new AtomicReference<>(first);
            AtomicInteger calls = new AtomicInteger();
            Supplier<Object> supplier = () -> { calls.incrementAndGet(); return result.get(); };
            var bind = type.getMethod("setPlayerSkinSupplier", Supplier.class);
            var skin = type.getMethod("getSkin");
            bind.invoke(view, supplier);
            assertEquals(1, calls.get(), "Only one lookup per binding update");
            assertEquals(first, skin.invoke(view));
            result.set(null);
            bind.invoke(view, supplier);
            assertEquals(first, skin.invoke(view));
            bind.invoke(view, (Supplier<Object>) () -> null);
            assertNull(skin.invoke(view), "A different player cannot inherit the old skin");
            result.set(first); bind.invoke(view, supplier);
            bind.invoke(view, new Object[]{null});
            assertNull(skin.invoke(view));
        }
    }
}
