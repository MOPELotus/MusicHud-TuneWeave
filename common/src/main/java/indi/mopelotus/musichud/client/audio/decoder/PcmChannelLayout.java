package indi.mopelotus.musichud.client.audio.decoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Speaker bits and ordering from RFC 9639 sections 8.6.2 and 9.1.3. */
final class PcmChannelLayout {
    private PcmChannelLayout() {}

    static long flacDefault(int channels) throws IOException {
        return switch (channels) {
            case 1 -> 4;
            case 2 -> 3;
            case 3 -> 7;
            case 4 -> 0x33;
            case 5 -> 0x37;
            case 6 -> 0x3f;
            case 7 -> 0x70f;
            case 8 -> 0x63f;
            default -> throw new IOException("Unsupported FLAC channel count");
        };
    }

    static void validate(int channels, long mask, boolean allowUnassigned) throws IOException {
        int assigned = Long.bitCount(mask);
        if (channels < 1 || channels > 8 || mask == 0 || (mask & ~0x3ffffL) != 0
                || assigned > channels || !allowUnassigned && assigned != channels)
            throw new IOException("Unsupported or inconsistent PCM speaker layout");
    }

    /** Null means no override. Unassigned trailing FLAC channels are intentionally not rendered. */
    static Long vorbisMask(byte[] metadata) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN);
        skipString(bytes);
        long count = length(bytes);
        if (count > bytes.remaining() / 4) throw new IOException("Invalid FLAC comment count");
        Long mask = null;
        for (long i = 0; i < count; i++) {
            int size = checkedSize(bytes);
            byte[] field = new byte[size]; bytes.get(field);
            String value = new String(field, StandardCharsets.UTF_8);
            int separator = value.indexOf('=');
            if (separator < 0 || !value.substring(0, separator).equalsIgnoreCase("WAVEFORMATEXTENSIBLE_CHANNEL_MASK")) continue;
            String encoded = value.substring(separator + 1);
            if (mask != null || !encoded.matches("(?i)0x[0-9a-f]+")) throw new IOException("Invalid FLAC channel mask tag");
            String digits = encoded.substring(2).replaceFirst("^0+", "");
            if (digits.length() > 8) throw new IOException("FLAC channel mask exceeds 32 bits");
            mask = digits.isEmpty() ? 0 : Long.parseLong(digits, 16);
        }
        if (bytes.hasRemaining()) throw new IOException("Trailing FLAC comment bytes");
        return mask;
    }

    private static long length(ByteBuffer bytes) throws IOException {
        if (bytes.remaining() < 4) throw new IOException("Truncated FLAC comment");
        return Integer.toUnsignedLong(bytes.getInt());
    }
    private static int checkedSize(ByteBuffer bytes) throws IOException {
        long size = length(bytes);
        if (size > bytes.remaining()) throw new IOException("Truncated FLAC comment field");
        return (int) size;
    }
    private static void skipString(ByteBuffer bytes) throws IOException {
        int size = checkedSize(bytes); bytes.position(bytes.position() + size);
    }
}
