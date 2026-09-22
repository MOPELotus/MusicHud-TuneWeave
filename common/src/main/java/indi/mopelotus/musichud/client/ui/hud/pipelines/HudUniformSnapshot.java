package indi.mopelotus.musichud.client.ui.hud.pipelines;

import net.minecraft.client.renderer.DynamicGpuDataStorage;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import java.util.Arrays;

/** The bytes submitted by one frame, independent of mutable layouts and animation clocks. */
public final class HudUniformSnapshot implements DynamicGpuDataStorage.DynamicGpuData {
    private final byte[] bytes;

    private HudUniformSnapshot(byte[] bytes) {
        this.bytes = bytes;
    }

    public static HudUniformSnapshot capture(HudUniform uniform) {
        // Std140Builder's JOML matrix writer uses native addresses. A heap ByteBuffer
        // can crash the VM here, even though scalar-only uniforms happen to work.
        // Zero the std140 padding as well, keeping equality deterministic.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.calloc(uniform.getUBOSize());
            uniform.write(buffer);
            byte[] bytes = new byte[uniform.getUBOSize()];
            buffer.position(0).get(bytes);
            return new HudUniformSnapshot(bytes);
        }
    }

    public int size() { return bytes.length; }

    @Override public void write(ByteBuffer buffer) { buffer.put(bytes); }

    @Override public boolean equals(Object other) {
        return other instanceof HudUniformSnapshot snapshot && Arrays.equals(bytes, snapshot.bytes);
    }

    @Override public int hashCode() { return Arrays.hashCode(bytes); }
}
