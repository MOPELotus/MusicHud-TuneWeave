package indi.mopelotus.musichud.companion.desktop;

import indi.mopelotus.musichud.companion.protocol.CompanionWire;

/** Immutable receiver state. A new connection must use a fresh instance. */
record DisplayState(long generation, long sequence, long receivedNanos, boolean locked,
                    CompanionWire.Message frame) {
    static DisplayState empty() { return new DisplayState(-1, -1, 0, true, null); }

    DisplayState accept(CompanionWire.Message message, long now) {
        if (generation >= 0 && (message.generation() != generation || message.sequence() <= sequence)) return this;
        var nextFrame = switch (message.kind()) {
            case FRAME -> message;
            case CLEAR -> null;
            case STATUS -> frame;
        };
        return new DisplayState(message.generation(), message.sequence(), now, message.locked(), nextFrame);
    }

    boolean expired(long now) { return receivedNanos == 0 || now - receivedNanos > 2_000_000_000L; }
}
