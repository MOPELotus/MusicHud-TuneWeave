package indi.mopelotus.musichud.client.services.tuneweave;

import java.util.List;

public record TuneWeaveVideo(String reference, String title, String description, String coverUrl,
                             int durationMillis, String publishedAt, long playCount,
                             List<TuneWeaveVideoCreator> creators, int partCount) {
    public TuneWeaveVideo {
        creators = creators == null ? List.of() : List.copyOf(creators);
    }
}
