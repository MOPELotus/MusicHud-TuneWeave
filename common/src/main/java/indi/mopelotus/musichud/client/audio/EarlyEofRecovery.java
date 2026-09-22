package indi.mopelotus.musichud.client.audio;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** One public refresh report per revision, with an overall budget for this playback session. */
final class EarlyEofRecovery {
    private UUID session;
    private final Set<Integer> reported = new HashSet<>();

    synchronized void activate(UUID value) {
        if (!value.equals(session)) { session = value; reported.clear(); }
    }
    synchronized boolean claim(UUID expected, int revision) {
        return expected.equals(session) && reported.size() < 3 && reported.add(revision);
    }
}
