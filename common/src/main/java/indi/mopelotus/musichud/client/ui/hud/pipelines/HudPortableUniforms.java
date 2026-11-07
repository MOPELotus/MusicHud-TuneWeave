package indi.mopelotus.musichud.client.ui.hud.pipelines;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BiConsumer;

/** Transports the existing std140 snapshot through Minecraft's plain-uniform shader API. */
public final class HudPortableUniforms {
    private HudPortableUniforms() {}

    public static void upload(HudUniform uniform, BiConsumer<String, float[]> sink) {
        var bytes = ByteBuffer.allocate(uniform.getUBOSize()).order(ByteOrder.nativeOrder());
        uniform.write(bytes);
        String block = uniform.getUBOName();
        if (block.endsWith("Position")) {
            emit(bytes, sink, "u_Translation", 0, 16);
            emit(bytes, sink, "u_Style", 64, 4);
        } else switch (block) {
            case "MHNowPlayingThemeColor" -> {
                emit(bytes, sink, "u_Primary", 0, 4);
                emit(bytes, sink, "u_Secondary", 16, 4);
                emit(bytes, sink, "u_Bright", 32, 4);
                emit(bytes, sink, "u_Dark", 48, 4);
            }
            case "MHProgressStyle" -> {
                emit(bytes, sink, "u_Gradient", 0, 3);
                emit(bytes, sink, "u_PlayedColor", 16, 4);
                emit(bytes, sink, "u_CurrentColor", 32, 4);
                emit(bytes, sink, "u_BackgroundColor", 48, 4);
            }
            case "MHDynamicStatus" -> emit(bytes, sink, "u_Dynamic1", 0, 4);
            default -> throw new IllegalArgumentException("Unknown HUD uniform block: " + block);
        }
    }

    private static void emit(ByteBuffer bytes, BiConsumer<String, float[]> sink, String name, int offset, int count) {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) values[i] = bytes.getFloat(offset + i * Float.BYTES);
        sink.accept(name, values);
    }
}
