package indi.mopelotus.musichud.metadata;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.generic.AudioFileReader;
import org.jaudiotagger.audio.generic.Utils;
import org.jaudiotagger.logging.ErrorMessage;
import org.jaudiotagger.tag.FieldKey;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;

/** Read-only upload metadata; no tagger types escape into the client. */
public final class AudioMetadataReader {
    private static final int MAX_ARTWORK_BYTES = 8 * 1024 * 1024;

    private AudioMetadataReader() {}

    /** Null fields were not read; callers retain their existing fallback values. */
    public record Metadata(String title, String artist, String album, String coverDataUri,
                           Integer durationMillis, Long bitrate) {}

    public static Metadata read(Path path) {
        Objects.requireNonNull(path, "path");
        String title = null, artist = null, album = null, cover = null;
        Integer duration = null;
        Long bitrate = null;
        try {
            var audio = readFile(path);
            var tag = audio.getTag();
            if (tag != null) {
                if (!tag.getFirst(FieldKey.TITLE).isBlank()) title = tag.getFirst(FieldKey.TITLE);
                artist = tag.getFirst(FieldKey.ARTIST);
                album = tag.getFirst(FieldKey.ALBUM);
                var art = tag.getFirstArtwork();
                if (art != null && art.getBinaryData() != null && art.getBinaryData().length <= MAX_ARTWORK_BYTES) {
                    String mime = Objects.requireNonNullElse(art.getMimeType(), "image/jpeg");
                    if (mime.equals("image/png") || mime.equals("image/jpeg")) {
                        cover = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(art.getBinaryData());
                    }
                }
            }
            var header = audio.getAudioHeader();
            if (header != null) {
                duration = (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(header.getPreciseTrackLength() * 1000)));
                if (header.getBitRateAsNumber() > 0) bitrate = header.getBitRateAsNumber() * 1000;
            }
        } catch (Exception ignored) {
            // Keep partial fields and the caller's filename/bitrate fallback for unreadable tags.
        }
        return new Metadata(title, artist, album, cover, duration, bitrate);
    }

    private static AudioFile readFile(Path path) throws Exception {
        String extension = Utils.getExtension(path.toFile());
        // Same public readers and extension normalization as AudioFileIO.read, without writers.
        // A fresh reader avoids sharing format-specific mutable state between uploads.
        AudioFileReader reader = switch (extension) {
            case "ogg", "oga" -> new org.jaudiotagger.audio.ogg.OggFileReader();
            case "flac" -> new org.jaudiotagger.audio.flac.FlacFileReader();
            case "mp3" -> new org.jaudiotagger.audio.mp3.MP3FileReader();
            case "mp4", "m4a", "m4p", "m4b" -> new org.jaudiotagger.audio.mp4.Mp4FileReader();
            case "wav" -> new org.jaudiotagger.audio.wav.WavFileReader();
            case "wma" -> new org.jaudiotagger.audio.asf.AsfFileReader();
            case "aif", "aiff", "aifc" -> new org.jaudiotagger.audio.aiff.AiffFileReader();
            case "dsf" -> new org.jaudiotagger.audio.dsf.DsfFileReader();
            case "dff" -> new org.jaudiotagger.audio.dff.DffFileReader();
            case "ra", "rm" -> new org.jaudiotagger.audio.real.RealFileReader();
            default -> throw new CannotReadException(ErrorMessage.NO_READER_FOR_THIS_FORMAT.getMsg(extension));
        };
        var audio = reader.read(path.toFile());
        audio.setExt(extension);
        return audio;
    }
}
