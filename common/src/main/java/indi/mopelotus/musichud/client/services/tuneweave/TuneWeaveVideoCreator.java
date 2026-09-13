package indi.mopelotus.musichud.client.services.tuneweave;

import java.util.Objects;

public record TuneWeaveVideoCreator(String reference, String name, String avatarUrl) {
    public TuneWeaveVideoCreator {
        reference = Objects.requireNonNullElse(reference, "");
        name = Objects.requireNonNullElse(name, "");
        avatarUrl = Objects.requireNonNullElse(avatarUrl, "");
    }
}
