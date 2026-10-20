package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.*;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZonedDateTimeCodecTest {
    @Test void preservesNanosecondsNegativeEpochAndZonedExtremes() {
        for (int nanos : new int[] {0, 1, 987654321, 999999999}) {
            for (String zone : List.of("UTC", "Asia/Shanghai", "America/New_York", "+05:45")) {
                for (long seconds : new long[] {-123456789, -1, 0, 1, 1792434123}) {
                    var source = ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos), ZoneId.of(zone));
                    assertEquals(source, roundTrip(Codecs.ZONED_DATE_TIME, source));
                }
            }
        }
        for (var source : List.of(ZonedDateTime.of(LocalDateTime.MIN, ZoneOffset.UTC),
                ZonedDateTime.of(LocalDateTime.MAX, ZoneOffset.UTC), PlaybackSession.NONE.startTime())) {
            assertEquals(source, roundTrip(Codecs.ZONED_DATE_TIME, source));
        }
    }

    @Test void preservesBothOffsetsInADaylightSavingOverlap() {
        var local = LocalDateTime.of(2026, 11, 1, 1, 30, 15, 987654321);
        var zone = ZoneId.of("America/New_York");
        var offsets = zone.getRules().getValidOffsets(local);
        assertEquals(2, offsets.size());
        var first = ZonedDateTime.ofLocal(local, zone, offsets.get(0));
        var second = ZonedDateTime.ofLocal(local, zone, offsets.get(1));
        assertNotEquals(first.toInstant(), second.toInstant());
        assertEquals(first, roundTrip(Codecs.ZONED_DATE_TIME, first));
        assertEquals(second, roundTrip(Codecs.ZONED_DATE_TIME, second));
    }

    @Test void everyAuthoritativeDeliveryPreservesTheSameInstant() {
        var time = ZonedDateTime.of(2026, 10, 20, 1, 2, 3, 987654321, ZoneId.of("Asia/Shanghai"));
        var session = new PlaybackSession(UUID.randomUUID(), 7, 2, MusicDetail.NONE, MusicResourceInfo.NONE, time);
        assertEquals(time, roundTrip(PlaybackSession.CODEC, session).startTime());
        assertEquals(time, roundTrip(SwitchMusicMessage.CODEC,
                new SwitchMusicMessage(session, MusicDetail.NONE, "", 9)).playbackSession().startTime());
        assertEquals(time, roundTrip(SyncCurrentPlayingMessage.CODEC,
                new SyncCurrentPlayingMessage(session, MusicDetail.NONE, 9)).playbackSession().startTime());
        var initial = new GetInitialStateResponse(session, MusicDetail.NONE, new ArrayDeque<>(), List.of(), List.of(), 9);
        initial.setRequestId(UUID.randomUUID());
        var decoded = roundTrip(GetInitialStateResponse.CODEC, initial);
        assertEquals(time, decoded.getPlaybackSession().startTime());
        assertEquals(initial.getRequestId(), decoded.getRequestId());
    }

    @Test void rejectsNonCanonicalNanosecondsInsteadOfNormalizingThem() {
        for (int nanos : new int[] {Integer.MIN_VALUE, -1, 1000000000, Integer.MAX_VALUE}) {
            var buffer = Unpooled.buffer();
            try {
                buffer.writeLong(0).writeInt(nanos);
                Codecs.STRING_UTF8.encode(buffer, "UTC");
                assertThrows(DecoderException.class, () -> Codecs.ZONED_DATE_TIME.decode(buffer));
            } finally { buffer.release(); }
        }
    }

    @Test void rejectsInvalidEpochZoneAndOversizedZoneString() {
        for (long epoch : new long[] {Long.MIN_VALUE, Long.MAX_VALUE}) {
            var buffer = Unpooled.buffer();
            try {
                buffer.writeLong(epoch).writeInt(1);
                Codecs.STRING_UTF8.encode(buffer, "UTC");
                assertThrows(DecoderException.class, () -> Codecs.ZONED_DATE_TIME.decode(buffer));
            } finally { buffer.release(); }
        }
        for (String zone : List.of("", "Not/A_Zone", "+99:00")) {
            var buffer = Unpooled.buffer();
            try {
                buffer.writeLong(0).writeInt(0);
                Codecs.STRING_UTF8.encode(buffer, zone);
                assertThrows(DecoderException.class, () -> Codecs.ZONED_DATE_TIME.decode(buffer));
            } finally { buffer.release(); }
        }
        var buffer = Unpooled.buffer();
        try {
            buffer.writeLong(0).writeInt(0);
            VanillaVarInt.write(buffer, 32768 * 3 + 1);
            assertThrows(DecoderException.class, () -> Codecs.ZONED_DATE_TIME.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void rejectsEveryTruncatedPrefixAndDoesNotConsumeFollowingFields() {
        var source = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(987654321);
        var buffer = Unpooled.buffer();
        try {
            Codecs.ZONED_DATE_TIME.encode(buffer, source);
            int size = buffer.writerIndex();
            for (int prefix = 0; prefix < size; prefix++) {
                var partial = buffer.slice(0, prefix);
                assertThrows(RuntimeException.class, () -> Codecs.ZONED_DATE_TIME.decode(partial));
            }
            buffer.writeLong(0x123456789abcdef0L);
            assertEquals(source, Codecs.ZONED_DATE_TIME.decode(buffer));
            assertEquals(0x123456789abcdef0L, buffer.readLong());
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
    }

    private static <T> T roundTrip(ByteBufCodec<T> codec, T source) {
        var buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, source);
            T decoded = codec.decode(buffer);
            assertFalse(buffer.isReadable());
            return decoded;
        } finally { buffer.release(); }
    }
}
