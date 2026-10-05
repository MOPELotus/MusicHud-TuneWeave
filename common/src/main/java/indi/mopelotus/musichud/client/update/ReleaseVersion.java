package indi.mopelotus.musichud.client.update;

import java.util.List;
import java.util.regex.Pattern;

/** Product SemVer, separate from the Minecraft network protocol and the CF package revision. */
public record ReleaseVersion(int major, int minor, int patch, List<String> pre, int cfRevision)
        implements Comparable<ReleaseVersion> {
    private static final Pattern FORMAT = Pattern.compile("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(?:-([A-Za-z0-9.-]{1,100}))?");
    public ReleaseVersion { pre = List.copyOf(pre); }
    public static ReleaseVersion parse(String text) {
        if (text == null || text.length() > 160) throw new IllegalArgumentException("Invalid release version");
        String[] parts = text.split("\\+", -1);
        if (parts.length > 2 || parts.length == 2 && !parts[1].matches("[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*")) throw new IllegalArgumentException("Invalid build metadata");
        String version = parts[0];
        int revision = 0;
        var cf = Pattern.compile("-cf\\.([1-9][0-9]{0,5})$").matcher(version);
        if (cf.find()) { revision = Integer.parseInt(cf.group(1)); version = version.substring(0, cf.start()); }
        var match = FORMAT.matcher(version);
        if (!match.matches()) throw new IllegalArgumentException("Invalid release version");
        List<String> pre = match.group(4) == null ? List.of() : List.of(match.group(4).split("\\.", -1));
        if (pre.stream().anyMatch(p -> p.isEmpty() || p.matches("0[0-9]+"))) throw new IllegalArgumentException("Invalid prerelease");
        return new ReleaseVersion(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)), pre, revision);
    }
    @Override public int compareTo(ReleaseVersion other) {
        int n = Integer.compare(major, other.major); if (n != 0) return n;
        n = Integer.compare(minor, other.minor); if (n != 0) return n;
        n = Integer.compare(patch, other.patch); if (n != 0) return n;
        if (pre.isEmpty() != other.pre.isEmpty()) return pre.isEmpty() ? 1 : -1;
        for (int i = 0; i < Math.min(pre.size(), other.pre.size()); i++) {
            String a = pre.get(i), b = other.pre.get(i); boolean an = a.matches("[0-9]+"), bn = b.matches("[0-9]+");
            var legacyA = Pattern.compile("(alpha|beta|rc)-([0-9]+)").matcher(a);
            var legacyB = Pattern.compile("(alpha|beta|rc)-([0-9]+)").matcher(b);
            if (legacyA.matches() && legacyB.matches() && legacyA.group(1).equals(legacyB.group(1))) {
                n = new java.math.BigInteger(legacyA.group(2)).compareTo(new java.math.BigInteger(legacyB.group(2)));
                if (n != 0) return n;
                continue;
            }
            n = an && bn ? new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b))
                    : an != bn ? (an ? -1 : 1) : a.compareTo(b);
            if (n != 0) return n;
        }
        n = Integer.compare(pre.size(), other.pre.size());
        return n != 0 ? n : Integer.compare(cfRevision, other.cfRevision);
    }
}
