package indi.mopelotus.musichud.client.ui.screen;

import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import java.util.Collection;

/** A single normal line is the provider's instrumental placeholder. */
final class LyricsPanelPolicy {
    private LyricsPanelPolicy() {}
    static boolean hasLyrics(Collection<LyricLine> lines) {
        if (lines == null) return false;
        return lines.stream().filter(line -> line != null && line.getType() == LyricLine.Type.NORMAL)
                .limit(2).count() > 1;
    }
}
