package indi.mopelotus.musichud.platform.mod.config;

import indi.mopelotus.musichud.MusicHud;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SimpleTomlConfig {
    private SimpleTomlConfig() {
    }

    static Path path(String fileName) {
        return indi.mopelotus.musichud.utils.LegacyDataMigration.configFile(MusicHud.getConfigDirectory(), fileName);
    }

    static Map<String, String> read(Path path) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) {
                    continue;
                }
                int splitIndex = trimmed.indexOf('=');
                if (splitIndex <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, splitIndex).trim();
                String value = stripInlineComment(trimmed.substring(splitIndex + 1).trim());
                values.put(key, unquote(value));
            }
        } catch (IOException e) {
            MusicHud.LOGGER.warn("Failed to read config file {}", path, e);
        }
        return values;
    }

    static void write(Path path, List<Entry> entries) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write("# MusicHud TuneWeave configuration");
                writer.newLine();
                writer.write("# This file is managed by MusicHud TuneWeave's built-in config loader.");
                writer.newLine();
                writer.newLine();
                for (Entry entry : entries) {
                    if (entry.comment != null && !entry.comment.isBlank()) {
                        for (String commentLine : entry.comment.split("\\R")) {
                            writer.write("# " + commentLine);
                            writer.newLine();
                        }
                    }
                    writer.write(entry.key + " = " + format(entry.value));
                    writer.newLine();
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            MusicHud.LOGGER.error("Failed to write config file {}", path, e);
        }
    }

    static boolean getBoolean(Map<String, String> values, String key, boolean defaultValue) {
        String value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return defaultValue;
    }

    static int getInt(Map<String, String> values, String key, int defaultValue) {
        String value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static double getDouble(Map<String, String> values, String key, double defaultValue) {
        String value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static String getString(Map<String, String> values, String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    static <E extends Enum<E>> E getEnum(Map<String, String> values, String key, Class<E> enumClass, E defaultValue) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    private static String stripInlineComment(String value) {
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                quoted = !quoted;
                continue;
            }
            if (current == '#' && !quoted) {
                return value.substring(0, i).trim();
            }
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return value;
        }
        StringBuilder builder = new StringBuilder(value.length() - 2);
        boolean escaped = false;
        for (int i = 1; i < value.length() - 1; i++) {
            char current = value.charAt(i);
            if (!escaped) {
                if (current == '\\') {
                    escaped = true;
                } else {
                    builder.append(current);
                }
                continue;
            }
            switch (current) {
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                default -> builder.append(current);
            }
            escaped = false;
        }
        if (escaped) {
            builder.append('\\');
        }
        return builder.toString();
    }

    private static String format(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return quote(String.valueOf(value == null ? "" : value));
    }

    private static String quote(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    record Entry(String key, String comment, Object value) {
    }
}

