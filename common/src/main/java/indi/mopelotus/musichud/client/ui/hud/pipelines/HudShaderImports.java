package indi.mopelotus.musichud.client.ui.hud.pipelines;

import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Bounded imports for the three 1.21.1 HUD programs. */
final class HudShaderImports {
    static final int MAX_SOURCE_BYTES = 1 << 20;
    private static final Pattern DIRECTIVE = Pattern.compile("(?m)^[ \\t]*#moj_import[ \\t]+<([^>\\r\\n]+)>[ \\t]*$");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+\\.glsl");

    static String expand(String source, Function<String, String> resolver) {
        if (source.length() > MAX_SOURCE_BYTES) throw new IllegalArgumentException("HUD shader source exceeds limit");
        var matcher = DIRECTIVE.matcher(source);
        var result = new StringBuilder();
        while (matcher.find()) {
            String id = matcher.group(1);
            if (!IDENTIFIER.matcher(id).matches() || id.contains("..") || id.contains(":/")) {
                throw new IllegalArgumentException("Invalid HUD shader import: " + id);
            }
            String imported = Objects.requireNonNull(resolver.apply(id), "Missing HUD shader import");
            if (imported.length() > MAX_SOURCE_BYTES || result.length() + imported.length() > MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException("HUD shader imports exceed limit");
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(imported));
        }
        matcher.appendTail(result);
        if (result.length() > MAX_SOURCE_BYTES) throw new IllegalArgumentException("HUD shader imports exceed limit");
        if (Pattern.compile("(?m)^[ \\t]*#moj_import\\b").matcher(result).find()) {
            throw new IllegalArgumentException("Malformed or nested HUD shader import");
        }
        return result.toString();
    }
}
