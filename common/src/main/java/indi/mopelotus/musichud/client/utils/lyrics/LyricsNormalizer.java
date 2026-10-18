package indi.mopelotus.musichud.client.utils.lyrics;

/** Normalizes display text while retaining UTF-16 boundaries used by lyric highlighting. */
public final class LyricsNormalizer {
    private LyricsNormalizer() {}

    public static String normalize(String text) {
        return withOffsets(text).text();
    }

    static Normalized withOffsets(String text) {
        if (text == null) text = "";
        StringBuilder output = new StringBuilder(text.length());
        int[] offsets = new int[text.length() + 1];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean whitespace = c == '\u00a0' || Character.isWhitespace(c);
            if (whitespace) {
                if (!output.isEmpty() && output.charAt(output.length() - 1) != ' ') output.append(' ');
            } else {
                output.append(c);
                if (c == ',') output.append(' ');
            }
            offsets[i + 1] = output.length();
        }
        if (!output.isEmpty() && output.charAt(output.length() - 1) == ' ') output.setLength(output.length() - 1);
        for (int i = 0; i < offsets.length; i++) offsets[i] = Math.min(offsets[i], output.length());
        return new Normalized(output.toString(), offsets);
    }

    record Normalized(String text, int[] offsets) {
        int offset(int sourceOffset) { return offsets[sourceOffset]; }
    }
}
