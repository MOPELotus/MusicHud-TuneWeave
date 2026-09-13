package indi.mopelotus.musichud.client.audio;

/** Local command input only; never a server-authorized resource selector. */
public final class PlaytestSource {
    private PlaytestSource() {}
    public static String parse(String input) {
        if (input == null || input.length() > 8192 || input.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid playtest input");
        String value = input.trim();
        if (value.startsWith("\"")) {
            if (value.length() < 2 || !value.endsWith("\"")) throw new IllegalArgumentException("Unclosed playtest path");
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank()) throw new IllegalArgumentException("Empty playtest input");
        return value;
    }
}
