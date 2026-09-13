package indi.mopelotus.musichud.beans.music;

import indi.mopelotus.musichud.beans.user.Profile;
import indi.mopelotus.musichud.server.playback.SharedResourceValidator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.time.ZonedDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackSourceTest {
    @Test void trackCodecPreservesIndependentNavigationContextWithoutMutatingOriginal() {
        var original = track();
        var source = new PlaybackSource("qq:playlist:2", "playlist", "Mix", "https://example.org/cover", "SEQUENTIAL");
        var requested = original.withPlaybackSource(source);
        assertEquals(PlaybackSource.NONE, original.getPlaybackSource());
        assertEquals("netease:1", requested.getSourceRef());
        var buffer = Unpooled.buffer();
        try {
            MusicDetail.CODEC.encode(buffer, requested);
            assertEquals(source, MusicDetail.CODEC.decode(buffer).getPlaybackSource());
        } finally { buffer.release(); }
    }

    @Test void publicSessionPreservesRequestedSourceInsteadOfResolverSubstitution() {
        var source = new PlaybackSource("netease:playlist:2", "playlist", "Original", "", "MANUAL");
        var requested = track().withPlaybackSource(source);
        var canonical = track().withPlaybackSource(new PlaybackSource("qq:other", "album", "Other", "", "RANDOM"));
        var resource = new MusicResourceInfo(1, "https://8.8.8.8/audio", 320000, 1000, FormatType.MP3, "", Fee.UNSET, 1000);
        var session = SharedResourceValidator.createSession(UUID.randomUUID(), 1, 0, requested, canonical, resource, ZonedDateTime.now());
        assertEquals(source, session.musicDetail().getPlaybackSource());
    }

    @Test void privateSourcesOmitIdentityAndImage() {
        var playlist = Playlist.fromTuneWeave(2, "netease:private", "Secret name", "https://example.org/private", 1, 0, Profile.ANONYMOUS);
        playlist.privacy = Privacy.PRIVATE;
        var source = PlaybackSource.from(playlist, "RANDOM");
        assertFalse(source.navigable());
        assertEquals("", source.reference());
        assertEquals("", source.imageUrl());
        assertNotEquals("Secret name", source.name());
    }

    @Test void rejectsMalformedSourceFieldsAndImageCredentials() {
        assertThrows(IllegalArgumentException.class, () -> new PlaybackSource("bad", "playlist", "Name", "", "MANUAL"));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackSource("qq:1", "invalid", "Name", "", "MANUAL"));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackSource("qq:1", "playlist", "x".repeat(501), "", "MANUAL"));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackSource("qq:1", "playlist", "Name", "file:///private", "MANUAL"));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackSource("qq:1", "playlist", "Name", "https://user:secret@example.org/a", "MANUAL"));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackSource("qq:1", "private", "Name", "", "MANUAL"));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackSource("qq:1", "playlist", "Name", "", "invalid"));
    }

    private static MusicDetail track() { return MusicDetail.fromTuneWeave(1, "netease:1", "track", "Track", 1000, Album.NONE, List.of()); }
}
