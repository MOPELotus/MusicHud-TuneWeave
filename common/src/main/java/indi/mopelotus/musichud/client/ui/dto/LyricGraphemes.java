package indi.mopelotus.musichud.client.ui.dto;

import com.ibm.icu.text.Bidi;
import com.ibm.icu.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Unicode drawing units; animation/timing indices remain indices of the original code points. */
final class LyricGraphemes {
    private LyricGraphemes() {}

    record Cluster(int start, int end, int codePointIndex, boolean rtl) {}

    static BreakIterator boundaries(CharSequence text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text.toString());
        return iterator;
    }

    static List<Cluster> visualOrder(CharSequence text, int start, int end) {
        String run = text.subSequence(start, end).toString();
        if (run.isEmpty()) return List.of();
        BreakIterator iterator = boundaries(run);
        Bidi bidi = new Bidi();
        bidi.setPara(run, Bidi.LEVEL_DEFAULT_LTR, null);
        List<Cluster> logical = new ArrayList<>();
        List<Byte> levels = new ArrayList<>();
        int codePointIndex = 0;
        for (int from = iterator.first(), to = iterator.next(); to != BreakIterator.DONE;
             from = to, to = iterator.next()) {
            byte level = bidi.getLevelAt(from);
            logical.add(new Cluster(start + from, start + to, codePointIndex, (level & 1) != 0));
            levels.add(level);
            codePointIndex += run.codePointCount(from, to);
        }
        byte[] clusterLevels = new byte[levels.size()];
        for (int i = 0; i < clusterLevels.length; i++) clusterLevels[i] = levels.get(i);
        int[] visualMap = Bidi.reorderVisual(clusterLevels);
        List<Cluster> visual = new ArrayList<>(logical.size());
        for (int index : visualMap) visual.add(logical.get(index));
        return List.copyOf(visual);
    }
}
