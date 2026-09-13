package indi.mopelotus.musichud.beans.music;

import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-authoritative state for one public playback. Lyrics are carried by the
 * canonical {@link MusicDetail}; credentials are deliberately not part of this contract.
 */
public record PlaybackSession(UUID sessionId, long sequence, int revision, MusicDetail musicDetail,
                              MusicResourceInfo resourceInfo, ZonedDateTime startTime) {
    private static final UUID NONE_ID = new UUID(0L, 0L);

    public static final ByteBufCodec<PlaybackSession> CODEC = ByteBufCodec.composite(
            Codecs.UUID, PlaybackSession::sessionId,
            Codecs.LONG, PlaybackSession::sequence,
            Codecs.INT, PlaybackSession::revision,
            MusicDetail.CODEC, PlaybackSession::musicDetail,
            MusicResourceInfo.CODEC, PlaybackSession::resourceInfo,
            Codecs.ZONED_DATE_TIME, PlaybackSession::startTime,
            PlaybackSession::new
    );

    public static final PlaybackSession NONE = new PlaybackSession(
            NONE_ID, 0, 0, MusicDetail.NONE, MusicResourceInfo.NONE,
            ZonedDateTime.of(LocalDateTime.MIN, ZoneOffset.UTC));

    public PlaybackSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(musicDetail, "musicDetail");
        Objects.requireNonNull(resourceInfo, "resourceInfo");
        Objects.requireNonNull(startTime, "startTime");
        if (sequence < 0 || revision < 0) {
            throw new IllegalArgumentException("sequence and revision cannot be negative");
        }
    }

    public static PlaybackSession stopped(long sequence) {
        return new PlaybackSession(NONE_ID, sequence, 0, MusicDetail.NONE,
                MusicResourceInfo.NONE, NONE.startTime());
    }

    public boolean isActive() {
        return !NONE_ID.equals(sessionId);
    }

    /** Returns whether this authoritative update supersedes the client's current snapshot. */
    public boolean supersedes(PlaybackSession current) {
        if (current == null || sequence > current.sequence) {
            return true;
        }
        return sequence == current.sequence
                && sessionId.equals(current.sessionId)
                && revision > current.revision;
    }
}
