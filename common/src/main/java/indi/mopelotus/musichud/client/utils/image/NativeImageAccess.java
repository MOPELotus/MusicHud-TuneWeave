package indi.mopelotus.musichud.client.utils.image;

import com.mojang.blaze3d.platform.NativeImage;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Modifier;

/** 1.21.1 exposes ABGR pixels and keeps its allocation address private. */
public final class NativeImageAccess {
    private static final VarHandle PIXELS = pixelsHandle();
    private NativeImageAccess() {}

    private static VarHandle pixelsHandle() {
        try {
            var candidates = java.util.Arrays.stream(NativeImage.class.getDeclaredFields())
                    .filter(field -> field.getType() == long.class && !Modifier.isStatic(field.getModifiers())
                            && !Modifier.isFinal(field.getModifiers())).toList();
            if (candidates.size() != 1) throw new IllegalStateException("Ambiguous NativeImage allocation field");
            return MethodHandles.privateLookupIn(NativeImage.class, MethodHandles.lookup())
                    .unreflectVarHandle(candidates.getFirst());
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }

    public static long pixels(NativeImage image) {
        long address = (long) PIXELS.get(image);
        if (address == 0) throw new IllegalStateException("NativeImage is closed");
        return address;
    }

    public static int argb(int abgr) {
        return (abgr & 0xff00ff00) | ((abgr & 0xff) << 16) | ((abgr >>> 16) & 0xff);
    }
}
