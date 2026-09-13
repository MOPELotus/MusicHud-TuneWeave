package indi.mopelotus.musichud.beans.music;

import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import java.util.Objects;
import java.util.Set;

/** Public navigation context, separate from the track's provider identity. */
public record PlaybackSource(String reference, String kind, String name, String imageUrl, String mode) {
    public static final PlaybackSource NONE = new PlaybackSource("", "", "", "", "MANUAL");
    public static final ByteBufCodec<PlaybackSource> CODEC = ByteBufCodec.composite(
            Codecs.STRING_UTF8, PlaybackSource::reference, Codecs.STRING_UTF8, PlaybackSource::kind,
            Codecs.STRING_UTF8, PlaybackSource::name, Codecs.STRING_UTF8, PlaybackSource::imageUrl,
            Codecs.STRING_UTF8, PlaybackSource::mode, PlaybackSource::new);

    public PlaybackSource {
        Objects.requireNonNull(reference); Objects.requireNonNull(kind); Objects.requireNonNull(name);
        Objects.requireNonNull(imageUrl); Objects.requireNonNull(mode);
        if (reference.length() > 512 || name.length() > 500 || imageUrl.length() > 2048
                || !Set.of("", "playlist", "album", "private").contains(kind)
                || !Set.of("MANUAL", "RANDOM", "SEQUENTIAL", "INTELLIGENT").contains(mode)
                || (!reference.isEmpty() && !reference.contains(":"))) throw new IllegalArgumentException("Invalid playback source");
        if (!imageUrl.isEmpty()) {
            var uri = java.net.URI.create(imageUrl);
            if (!Set.of("http", "https").contains(Objects.requireNonNullElse(uri.getScheme(), ""))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("Invalid source image URL");
        }
        if (kind.equals("private") && (!reference.isEmpty() || !imageUrl.isEmpty())) throw new IllegalArgumentException("Private source exposes identity");
    }

    public static PlaybackSource from(MusicCollection collection, String mode) {
        if (collection instanceof Playlist playlist && playlist.getPrivacy() == Privacy.PRIVATE) {
            return new PlaybackSource("", "private", "Private Playlist", "", mode);
        }
        String ref = collection instanceof Playlist p ? p.getSourceRef() : ((Album) collection).getSourceRef();
        String image = collection.getImageThumbnailUrl(240);
        if (image == null || image.startsWith("data:") || image.length() > 2048) image = "";
        return new PlaybackSource(ref, collection instanceof Playlist ? "playlist" : "album", collection.getName(), image, mode);
    }

    public boolean navigable() { return !reference.isBlank() && (kind.equals("playlist") || kind.equals("album")); }
}
