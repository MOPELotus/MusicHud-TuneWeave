package indi.mopelotus.musichud.client.ui.hud.renderer.text;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GradientVertexConsumerTest {
    @Test void capturesLocalPositionBeforeTransformAndKeepsFluentCallsIntercepted() {
        List<String> methods = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        VertexConsumer delegate = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    methods.add(method.getName());
                    if (method.getName().equals("setColor")) colors.add((Integer) args[0]);
                    return proxy;
                });
        var consumer = new GradientVertexConsumer(delegate, 10, 100,
                GradientSpec.twoStop(0xFF000000, 0xFFFFFFFF), .5f);
        var transform = new Matrix4f().translate(1000, 0, 0).scale(2);
        assertSame(consumer, consumer.addVertex(transform, 60, 2, 0).setUv(.1f, .2f).setColor(0xFF000000));
        assertEquals(List.of("addVertex", "setUv", "setColor"), methods);
        assertEquals(0x7F808080, colors.getFirst());
        consumer.addVertex(999, 2, 0).setColor(0xFF123456);
        assertEquals(0xFF123456, colors.getLast());
    }

    @Test void colorChannelOverloadCannotBypassGradientAndAuxiliaryAttributesAreUnchanged() {
        List<String> methods = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        VertexConsumer delegate = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    methods.add(method.getName());
                    if (method.getName().equals("setColor") && args.length == 1) colors.add((Integer) args[0]);
                    return proxy;
                });
        var consumer = new GradientVertexConsumer(delegate, 0, 10, GradientSpec.flat(0x80402010), 1);
        consumer.addVertex(new Matrix4f(), 5, 0, 0).setColor(255, 255, 255, 255)
                .setUv1(1, 2).setUv2(3, 4).setNormal(0, 1, 0).setLineWidth(2);
        assertEquals(List.of(0x80402010), colors);
        assertEquals(List.of("addVertex", "setColor", "setUv1", "setUv2", "setNormal", "setLineWidth"), methods);
    }
}
