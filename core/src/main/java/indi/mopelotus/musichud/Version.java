package indi.mopelotus.musichud;

import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import java.util.Objects;

public record Version(long major, long minor, long patch, BuildType build) implements Comparable<Version> {
    public static final ByteBufCodec<Version> PACKET_CODEC = ByteBufCodec.composite(
            Codecs.LONG, Version::major,
            Codecs.LONG, Version::minor,
            Codecs.LONG, Version::patch,
            Codecs.ofEnum(BuildType.class), Version::build,
            Version::new
    );
    public static final Version CURRENT = new Version(2, 0, 0, BuildType.Alpha);
    public static final Version LEAST_COMPATIBLE = new Version(2, 0, 0, BuildType.Alpha);

    public Version {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version components cannot be negative");
        }
        Objects.requireNonNull(build, "build");
    }

    public enum BuildType {
        Alpha("alpha"), Beta("beta"), PreRelease("pre-release"), Stable("stable");
        final String name;
        BuildType(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + "-" + build;
    }

    @Override
    public int compareTo(Version other) {
        int result = Long.compare(major, other.major);
        if (result == 0) result = Long.compare(minor, other.minor);
        if (result == 0) result = Long.compare(patch, other.patch);
        if (result == 0) result = Integer.compare(build.ordinal(), other.build.ordinal());
        return result;
    }

    public static boolean compatibleWith(Version version) {
        return version != null
                && version.major == CURRENT.major
                && LEAST_COMPATIBLE.compareTo(version) <= 0;
    }
}
