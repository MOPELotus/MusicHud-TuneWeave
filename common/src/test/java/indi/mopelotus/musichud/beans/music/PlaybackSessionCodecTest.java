package indi.mopelotus.musichud.beans.music;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackSessionCodecTest {
    @Test
    void roundTripsSharedResourceLyricsAndTimeline() {
        MusicDetail track = MusicDetail.fromTuneWeave(42, "netease:track:42", "track",
                "Track", 90_000, Album.NONE, List.of());
        track.setSourcePartRef("netease:cloud:42");
        track.setCloudSource(true);
        track.setLyricInfo(new LyricInfo(
                new Lyric("[00:00.000]line"), Lyric.NONE, Lyric.NONE, Lyric.NONE));
        MusicResourceInfo resource = new MusicResourceInfo(
                42, "https://8.8.8.8/audio", 320000, 1234,
                FormatType.MP3, "abc", Fee.UNSET, 90_000);
        resource.setQualityMetadata(Quality.LOSSLESS, Quality.HIGHER);
        resource.setResolvedTrackReference("netease:resolved:42");
        PlaybackSession source = new PlaybackSession(
                UUID.randomUUID(), 9, 3, track, resource,
                ZonedDateTime.of(2026, 8, 8, 12, 30, 15, 0, ZoneOffset.UTC));
        ByteBuf buffer = Unpooled.buffer();
        try {
            PlaybackSession.CODEC.encode(buffer, source);
            PlaybackSession decoded = PlaybackSession.CODEC.decode(buffer);

            assertTrue(decoded.isActive());
            assertEquals(source.sessionId(), decoded.sessionId());
            assertEquals(9, decoded.sequence());
            assertEquals(3, decoded.revision());
            assertEquals("netease:track:42", decoded.musicDetail().getSourceRef());
            assertEquals("netease:cloud:42", decoded.musicDetail().getSourcePartRef());
            assertTrue(decoded.musicDetail().isCloudSource());
            assertEquals("[00:00.000]line",
                    decoded.musicDetail().getLyricInfo().getLyric().getLyric());
            assertEquals("https://8.8.8.8/audio", decoded.resourceInfo().getUrl());
            assertEquals(source.startTime(), decoded.startTime());
            assertEquals(Quality.LOSSLESS, decoded.resourceInfo().getRequestedQuality());
            assertEquals(Quality.HIGHER, decoded.resourceInfo().getActualQuality());
            assertEquals("netease:resolved:42", decoded.resourceInfo().getResolvedTrackReference());
        } finally {
            buffer.release();
        }
    }

    @Test
    void authoritativeSequenceRejectsLateSessionUpdates() {
        PlaybackSession stopped = PlaybackSession.stopped(12);
        PlaybackSession stale = new PlaybackSession(
                UUID.randomUUID(), 11, 0, MusicDetail.NONE, MusicResourceInfo.NONE,
                PlaybackSession.NONE.startTime());
        PlaybackSession current = new PlaybackSession(
                UUID.randomUUID(), 13, 0, MusicDetail.NONE, MusicResourceInfo.NONE,
                PlaybackSession.NONE.startTime());
        PlaybackSession refreshed = new PlaybackSession(
                current.sessionId(), current.sequence(), 1, MusicDetail.NONE,
                MusicResourceInfo.NONE, current.startTime());

        assertTrue(stopped.supersedes(PlaybackSession.NONE));
        assertFalse(stale.supersedes(stopped));
        assertTrue(current.supersedes(stopped));
        assertTrue(refreshed.supersedes(current));
        assertFalse(current.supersedes(refreshed));
    }
}
