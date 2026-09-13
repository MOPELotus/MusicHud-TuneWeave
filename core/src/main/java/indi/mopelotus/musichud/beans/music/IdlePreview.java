package indi.mopelotus.musichud.beans.music;

import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import java.util.Objects;
import java.util.UUID;

/** Next-track updates have their own order and never replace the current audio resource. */
public record IdlePreview(UUID sessionId, long sequence, long revision, MusicDetail music) {
    public static final ByteBufCodec<IdlePreview> CODEC = ByteBufCodec.composite(
            Codecs.UUID, IdlePreview::sessionId, Codecs.LONG, IdlePreview::sequence,
            Codecs.LONG, IdlePreview::revision, MusicDetail.CODEC, IdlePreview::music, IdlePreview::new);
    public IdlePreview {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(music);
        if (sequence < 0 || revision < 0) throw new IllegalArgumentException("Negative preview identity");
    }
    public boolean matches(PlaybackSession session) {
        return sequence == session.sequence() && sessionId.equals(session.sessionId());
    }
}
