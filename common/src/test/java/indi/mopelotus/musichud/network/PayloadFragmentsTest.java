package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.ServerPayloadFragment;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PayloadFragmentsTest {
    private static final String CHANNEL = "musichud_tuneweave:large_payload";

    @Test void rejectsGlobalReservationOverflowAndMalformedWireLength() {
        var assembler = new PayloadFragments.Assembler();
        int count = (PayloadFragments.MAX_BYTES + PayloadFragments.CHUNK_BYTES - 1) / PayloadFragments.CHUNK_BYTES;
        for (int i = 0; i < 4; i++) assembler.accept(new Object(), new PayloadFragments.Frame(UUID.randomUUID(), CHANNEL,
                0, count, PayloadFragments.MAX_BYTES, new byte[PayloadFragments.CHUNK_BYTES]), 0);
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(new Object(),
                PayloadFragments.split(CHANNEL, new byte[30_000]).getFirst(), 0));
        assembler.clear();
        var buffer = Unpooled.buffer();
        try {
            Codecs.UUID.encode(buffer, UUID.randomUUID()); Codecs.STRING_UTF8.encode(buffer, CHANNEL);
            buffer.writeInt(0).writeInt(2).writeInt(30_000).writeInt(-1);
            assertThrows(IllegalArgumentException.class, () -> PayloadFragments.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void outOfOrderAndDuplicateFramesDeliverExactlyOnce() {
        byte[] bytes = new byte[50_001]; new Random(42).nextBytes(bytes);
        var frames = PayloadFragments.split(CHANNEL, bytes);
        var assembler = new PayloadFragments.Assembler(); Object peer = new Object();
        assertNull(assembler.accept(peer, frames.get(2), 0));
        assertNull(assembler.accept(peer, frames.get(0), 0));
        assertNull(assembler.accept(peer, frames.get(0), 0));
        assertArrayEquals(bytes, assembler.accept(peer, frames.get(1), 1));
        frames.forEach(frame -> assertNull(assembler.accept(peer, frame, 2)));
        assertTrue(frames.stream().allMatch(frame -> frame.data().length <= 24_000));
    }

    @Test void identicalUuidOnDifferentConnectionCannotCombineAndForgetDropsPartial() {
        var frames = PayloadFragments.split(CHANNEL, new byte[30_000]);
        var assembler = new PayloadFragments.Assembler();
        String first = new String("same"), second = new String("same");
        assertNull(assembler.accept(first, frames.get(0), 0));
        assertNull(assembler.accept(second, frames.get(1), 1));
        assembler.forget(first);
        assertNull(assembler.accept(first, frames.get(1), 2));
        assertNotNull(assembler.accept(second, frames.get(0), 2));
    }

    @Test void timeoutAndConflictingMetadataDropPreviousAssembly() {
        var frames = PayloadFragments.split(CHANNEL, new byte[30_000]);
        var assembler = new PayloadFragments.Assembler(); Object peer = new Object();
        assembler.accept(peer, frames.get(0), 0);
        assertNull(assembler.accept(peer, frames.get(1), 10_000));
        var first = frames.get(0);
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(peer,
                new PayloadFragments.Frame(first.id(), "musichud_tuneweave:other", first.index(), first.count(), first.total(), first.data()), 10_001));
        assertNull(assembler.accept(peer, frames.get(0), 10_002));
        byte[] changed = first.data(); changed[0] = 1;
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(peer,
                new PayloadFragments.Frame(first.id(), CHANNEL, 0, first.count(), first.total(), changed), 10_003));
    }

    @Test void enforcesPendingBudgetsAndMalformedFrameBounds() {
        var assembler = new PayloadFragments.Assembler(); Object peer = new Object();
        for (int i = 0; i < 4; i++) assembler.accept(peer, PayloadFragments.split(CHANNEL, new byte[30_000]).getFirst(), 0);
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(peer, PayloadFragments.split(CHANNEL, new byte[30_000]).getFirst(), 0));
        assertThrows(IllegalArgumentException.class, () -> new PayloadFragments.Frame(UUID.randomUUID(), CHANNEL, -1, 2, 30_000, new byte[24_000]));
        assertThrows(IllegalArgumentException.class, () -> new PayloadFragments.Frame(UUID.randomUUID(), "foreign:test", 0, 2, 30_000, new byte[24_000]));
        assertThrows(IllegalArgumentException.class, () -> new PayloadFragments.Frame(UUID.randomUUID(), CHANNEL, 0, Integer.MAX_VALUE, 30_000, new byte[24_000]));
    }

    @Test void actualSenderAndCodecProducePluginSizedPackets() {
        var codec = ByteBufCodec.composite(Codecs.STRING_UTF8, LargePayload::value, LargePayload::new);
        PayloadFragments.register(LargePayload.class, codec, (payload, player) -> {}, false);
        List<S2CPayload> sent = new ArrayList<>();
        PayloadFragments.sendS2C(new LargePayload("x".repeat(30_000)), sent::add);
        assertEquals(2, sent.size());
        for (var payload : sent) {
            var fragment = assertInstanceOf(ServerPayloadFragment.class, payload);
            var buffer = Unpooled.buffer();
            try {
                ServerPayloadFragment.CODEC.encode(buffer, fragment);
                assertTrue(buffer.readableBytes() < 32766);
                assertArrayEquals(fragment.frame().data(), ServerPayloadFragment.CODEC.decode(buffer).frame().data());
            } finally { buffer.release(); }
        }
    }

    private record LargePayload(String value) implements S2CPayload {}
}
