package indi.mopelotus.musichud.client.services.tuneweave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record TuneWeaveUniItem(String id, int position, String kind, String sourceRef,
                               String title, List<String> artists, String album,
                               int durationMillis, String coverUrl) {
    public TuneWeaveUniItem {
        artists = artists == null
                ? List.of() : Collections.unmodifiableList(new ArrayList<>(artists));
    }
}
