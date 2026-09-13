package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.Version;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.*;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.*;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IdlePreviewCodecTest {
    @Test void previewAndEveryStateDeliveryCarryTheIndependentRevision() {
        var session = PlaybackSession.stopped(12);
        var preview = new IdlePreview(session.sessionId(), 12, 45, MusicDetail.NONE);
        assertEquals(preview, roundTrip(IdlePreview.CODEC, preview));
        assertEquals(45, roundTrip(UpdateNextToPlayMessage.CODEC, new UpdateNextToPlayMessage(preview)).preview().revision());
        assertEquals(45, roundTrip(SwitchMusicMessage.CODEC,
                new SwitchMusicMessage(session, MusicDetail.NONE, "", 45)).previewRevision());
        assertEquals(45, roundTrip(SyncCurrentPlayingMessage.CODEC,
                new SyncCurrentPlayingMessage(session, MusicDetail.NONE, 45)).previewRevision());
        var initial = new GetInitialStateResponse(session, MusicDetail.NONE, new ArrayDeque<>(), List.of(), List.of(), 45);
        initial.setRequestId(UUID.randomUUID());
        var decoded = roundTrip(GetInitialStateResponse.CODEC, initial);
        assertEquals(45, decoded.getPreviewRevision());
        assertEquals(initial.getRequestId(), decoded.getRequestId());
    }

    @Test void requestDecoderCreatesIndependentCorrelationState() {
        var source = new RotateNextToPlayRequest(UUID.randomUUID(), 4, 5);
        source.setRequestId(UUID.randomUUID());
        var first = roundTrip(RotateNextToPlayRequest.CODEC, source);
        var second = roundTrip(RotateNextToPlayRequest.CODEC, source);
        assertNotSame(first, second);
        second.setRequestId(UUID.randomUUID());
        assertEquals(source.getRequestId(), first.getRequestId());
        assertNotEquals(first.getRequestId(), second.getRequestId());
        assertEquals(source.getSessionId(), first.getSessionId());
        assertEquals(4, first.getSequence());
        assertEquals(5, first.getPreviewRevision());
    }

    @Test void rejectsTruncatedAndNegativeRequestIdentities() {
        var source = new RotateNextToPlayRequest(UUID.randomUUID(), 4, 5);
        source.setRequestId(UUID.randomUUID());
        var buffer = Unpooled.buffer();
        try {
            RotateNextToPlayRequest.CODEC.encode(buffer, source);
            for (int size = 0; size < buffer.writerIndex(); size++) {
                var partial = buffer.slice(0, size);
                assertThrows(RuntimeException.class, () -> RotateNextToPlayRequest.CODEC.decode(partial));
            }
            buffer.setLong(buffer.writerIndex() - 8, -1);
            assertThrows(IllegalArgumentException.class, () -> RotateNextToPlayRequest.CODEC.decode(buffer));
            assertThrows(IllegalArgumentException.class, () -> new IdlePreview(UUID.randomUUID(), -1, 0, MusicDetail.NONE));
            assertThrows(IllegalArgumentException.class, () -> new IdlePreview(UUID.randomUUID(), 0, -1, MusicDetail.NONE));
        } finally { buffer.release(); }
    }

    @Test void allStateCodecsRejectNegativePreviewRevision() {
        var session = PlaybackSession.stopped(1);
        rejectNegativeTail(SwitchMusicMessage.CODEC, new SwitchMusicMessage(session, MusicDetail.NONE, "", 1));
        rejectNegativeTail(SyncCurrentPlayingMessage.CODEC, new SyncCurrentPlayingMessage(session, MusicDetail.NONE, 1));
        var initial = new GetInitialStateResponse(session, MusicDetail.NONE, new ArrayDeque<>(), List.of(), List.of(), 1);
        initial.setRequestId(UUID.randomUUID());
        rejectNegativeTail(GetInitialStateResponse.CODEC, initial);
    }

    @Test void handshakeRejectsPeersUsingThePreviousPreviewLayout() {
        Set<ProtocolCapability> old = new HashSet<>(ProtocolInfo.CAPABILITIES);
        assertTrue(old.remove(ProtocolCapability.IDLE_PREVIEW_ROTATION));
        assertFalse(ProtocolInfo.isCompatible(ProtocolInfo.PROJECT_ID, Version.CURRENT, old));
    }

    private static <T> T roundTrip(ByteBufCodec<T> codec, T source) {
        var buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, source);
            T result = codec.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return result;
        } finally { buffer.release(); }
    }
    private static <T> void rejectNegativeTail(ByteBufCodec<T> codec, T source) {
        var buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, source);
            buffer.setLong(buffer.writerIndex() - 8, -1);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
        } finally { buffer.release(); }
    }
}
