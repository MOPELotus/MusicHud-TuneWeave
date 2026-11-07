package indi.mopelotus.musichud.client.compat;

import com.google.gson.JsonParser;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Validates the UI host without loading a class before Mixin transforms it. */
public final class ModernUiHost {
    public static final String DOWNLOAD = "https://github.com/MOPELotus/ModernUI-MC-Lumen/releases/tag/v3.13.0.10";
    private static final String OFFICIAL_VERSION = "";
    private static Boolean lumen;

    private ModernUiHost() {}

    public static synchronized boolean isLumen() {
        if (lumen != null) return lumen;
        String entry = "icyllis/modernui/mc/UIManager.class";
        URL target = ModernUiHost.class.getClassLoader().getResource(entry);
        if (target == null) throw unsupported("missing");
        String base = target.toExternalForm();
        base = base.substring(0, base.length() - entry.length());
        lumen = inspect(newUrl(base));
        return lumen;
    }

    private static URL newUrl(String base) {
        try { return new URL(base); }
        catch (java.net.MalformedURLException invalid) { throw unsupported("invalid host URL"); }
    }

    static boolean inspect(URL root) {
        String base = root.toExternalForm();
        String name = null, version = null, source = null;
        try {
            String text = read(new URL(base + "fabric.mod.json"));
            var json = JsonParser.parseString(text).getAsJsonObject();
            if (!"modernui".equals(json.get("id").getAsString())) throw unsupported("invalid mod identity");
            name = json.get("name").getAsString();
            version = json.get("version").getAsString();
            source = json.getAsJsonObject("contact").get("sources").getAsString();
        } catch (java.io.IOException missingFabric) {
            try {
                String text = read(new URL(base + "META-INF/neoforge.mods.toml"));
                if (!"modernui".equals(toml(text, "modId"))) throw unsupported("invalid mod identity");
                name = toml(text, "displayName");
                version = toml(text, "version");
                source = toml(text, "displayURL");
            } catch (Exception invalid) {
                throw unsupported("unreadable NeoForge metadata");
            }
        } catch (Exception invalid) {
            throw unsupported("unreadable Fabric metadata");
        }
        return accepts(name, version, source);
    }

    private static String read(URL url) throws java.io.IOException {
        try (var input = url.openStream()) {
            byte[] bytes = input.readNBytes(65537);
            if (bytes.length > 65536) throw new java.io.IOException("oversized UI host metadata");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String toml(String text, String key) {
        var match = Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*\"([^\"]+)\"").matcher(text);
        if (!match.find()) throw unsupported("missing " + key);
        return match.group(1);
    }

    /** Returns the selected ABI; all other distributions fail closed with update instructions. */
    public static boolean accepts(String name, String version, String source) {
        if ("ModernUI Lumen".equals(name) || "ModernUI Lumen (MiSans)".equals(name)) {
            if ("3.13.0.10".equals(version) && "https://github.com/MOPELotus/ModernUI-MC-Lumen".equals(source)) return true;
        }
        if (!OFFICIAL_VERSION.isEmpty() && OFFICIAL_VERSION.equals(version)
                && "Modern UI".equals(name)
                && ("https://github.com/BloCamLimb/ModernUI-MC".equals(source)
                    || "https://curseforge.com/minecraft/mc-mods/modern-ui".equals(source))) return false;
        throw unsupported(String.valueOf(name) + " " + version);
    }

    private static IllegalStateException unsupported(String installed) {
        return new IllegalStateException("ModernUI 版本过低 / ModernUI version is too old.\n"
                + "已安装 / Installed: " + installed + "\n"
                + "请下载适配当前 Minecraft 和加载器的 ModernUI Lumen 3.13.0.10，替换原有 ModernUI。\n"
                + "Download ModernUI Lumen 3.13.0.10 for your Minecraft version and loader and replace ModernUI.\n"
                + DOWNLOAD);
    }
}
