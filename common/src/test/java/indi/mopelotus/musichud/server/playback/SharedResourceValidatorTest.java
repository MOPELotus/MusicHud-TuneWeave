package indi.mopelotus.musichud.server.playback;

import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.Fee;
import indi.mopelotus.musichud.beans.music.FormatType;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.MusicResourceInfo;
import indi.mopelotus.musichud.beans.music.PlaybackSession;
import indi.mopelotus.musichud.beans.music.Quality;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedResourceValidatorTest {
    @Test
    void acceptsPublicHttpResourcesAndDropsCredentialHeaders() {
        MusicDetail track = track("netease:track:1");
        MusicResourceInfo resource = new MusicResourceInfo(
                track.getId(), "https://8.8.8.8/audio", 320000, 1000,
                FormatType.MP3, "", Fee.UNSET, 60_000,
                Map.of("User-Agent", "MusicHud", "Authorization", "secret"),
                List.of("https://1.1.1.1/backup"));

        resource.setQualityMetadata(Quality.HIRES, Quality.LOSSLESS);
        resource.setResolvedTrackReference("netease:resolved:1");
        PlaybackSession session = SharedResourceValidator.createSession(
                UUID.randomUUID(), 1, 0, track, track, resource, ZonedDateTime.now());

        assertTrue(session.isActive());
        assertEquals(Map.of("user-agent", "MusicHud"), session.resourceInfo().getHeaders());
        assertEquals(List.of("https://1.1.1.1/backup"), session.resourceInfo().getBackupUrls());
        assertEquals(Quality.HIRES, session.resourceInfo().getRequestedQuality());
        assertEquals(Quality.LOSSLESS, session.resourceInfo().getActualQuality());
        assertEquals("netease:resolved:1", session.resourceInfo().getResolvedTrackReference());
    }

    @Test
    void rejectsPrivateLoopbackAndDangerousSchemes() {
        assertThrows(IllegalArgumentException.class,
                () -> SharedResourceValidator.requireSafeHttpUrl("http://127.0.0.1/audio"));
        assertThrows(IllegalArgumentException.class,
                () -> SharedResourceValidator.requireSafeHttpUrl("http://192.168.1.10/audio"));
        assertThrows(IllegalArgumentException.class,
                () -> SharedResourceValidator.requireSafeHttpUrl("http://[::1]/audio"));
        assertThrows(IllegalArgumentException.class,
                () -> SharedResourceValidator.requireSafeHttpUrl("file:///etc/passwd"));
        assertThrows(java.net.UnknownHostException.class,
                () -> SharedResourceValidator.resolvePublicAddresses("127.0.0.1"));
    }

    @Test
    void rejectsResolverIdentitySubstitution() {
        MusicDetail requested = track("netease:track:1");
        MusicDetail substituted = track("netease:track:2");
        MusicResourceInfo resource = new MusicResourceInfo(
                substituted.getId(), "https://8.8.8.8/audio", 0, 0,
                FormatType.AUTO, "", Fee.UNSET, 60_000);

        assertThrows(IllegalArgumentException.class, () -> SharedResourceValidator.createSession(
                UUID.randomUUID(), 1, 0, requested, substituted, resource, ZonedDateTime.now()));
        assertFalse(PlaybackSession.NONE.isActive());
    }

    @Test
    void refreshKeepsSessionIdentityAndTimelineWhileIncreasingRevision() {
        MusicDetail track = track("netease:track:1");
        UUID sessionId = UUID.randomUUID();
        ZonedDateTime startTime = ZonedDateTime.now().withNano(0);
        PlaybackSession initial = SharedResourceValidator.createSession(
                sessionId, 7, 0, track, track, resource(track, "https://8.8.8.8/old"), startTime);
        PlaybackSession refreshed = SharedResourceValidator.createSession(
                initial.sessionId(), initial.sequence(), initial.revision() + 1, track, track,
                resource(track, "https://1.1.1.1/new"), initial.startTime());

        assertEquals(initial.sessionId(), refreshed.sessionId());
        assertEquals(initial.sequence(), refreshed.sequence());
        assertEquals(1, refreshed.revision());
        assertEquals(initial.startTime(), refreshed.startTime());
    }

    @Test
    void playbackSessionContractContainsNoCredentialField() {
        assertTrue(Arrays.stream(PlaybackSession.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .noneMatch(name -> name.contains("credential") || name.contains("cookie")
                        || name.contains("token") || name.contains("password")));
    }

    private static MusicDetail track(String reference) {
        return MusicDetail.fromTuneWeave(reference.hashCode(), reference, "track", "Track",
                60_000, Album.NONE, List.<Artist>of());
    }

    private static MusicResourceInfo resource(MusicDetail track, String url) {
        return new MusicResourceInfo(track.getId(), url, 0, 0,
                FormatType.AUTO, "", Fee.UNSET, track.getDurationMillis());
    }
}
