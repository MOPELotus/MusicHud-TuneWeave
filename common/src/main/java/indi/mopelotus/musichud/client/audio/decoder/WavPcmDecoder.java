package indi.mopelotus.musichud.client.audio.decoder;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** RIFF PCM reader with strict chunk bounds and frame-aligned incremental output. */
final class WavPcmDecoder implements AudioDecoder {
    private final InputStream input;
    private final PcmOutput output;
    private final boolean ieeeFloat;
    private long remaining;
    private long framesRead;
    private volatile boolean closed;

    WavPcmDecoder(InputStream input, boolean floatSupported) throws IOException {
        this(input, floatSupported, false);
    }

    WavPcmDecoder(InputStream input, boolean floatSupported, boolean multichannelSupported) throws IOException {
        this.input = input;
        byte[] riff = exact(input, 12);
        if (!tag(riff, 0).equals("RIFF") || !tag(riff, 8).equals("WAVE")) throw new IOException("Not a RIFF WAVE stream");
        long riffRemaining = unsigned(riff, 4) - 4;
        PcmOutput found = null;
        boolean floatingInput = false;
        long headerBytes = 12;
        for (int chunks = 0; chunks < 128; chunks++) {
            if (riffRemaining < 8) throw new IOException("Truncated RIFF chunk table");
            byte[] header = exact(input, 8);
            riffRemaining -= 8;
            String tag = tag(header, 0);
            long size = unsigned(header, 4);
            if (size > riffRemaining) throw new IOException("WAVE chunk exceeds RIFF size");
            if (tag.equals("data")) {
                if (found == null || size % (found.channels * found.bits / 8) != 0) throw new IOException("Invalid WAVE data alignment");
                output = found; ieeeFloat = floatingInput; remaining = size;
                return;
            }
            if (size > 1_048_576 - headerBytes) throw new IOException("WAVE header too large");
            if (tag.equals("fmt ")) {
                if (found != null || size < 16 || size > 4096) throw new IOException("Invalid WAVE format chunk");
                byte[] format = exact(input, (int) size);
                var bytes = ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN);
                int encoding = Short.toUnsignedInt(bytes.getShort());
                int channels = Short.toUnsignedInt(bytes.getShort());
                int rate = bytes.getInt();
                long byteRate = Integer.toUnsignedLong(bytes.getInt());
                int alignment = Short.toUnsignedInt(bytes.getShort());
                int bits = Short.toUnsignedInt(bytes.getShort());
                long channelMask = channels == 1 ? 4 : channels == 2 ? 3 : 0;
                if (encoding == 65534) {
                    if (size < 40 || Short.toUnsignedInt(bytes.getShort(16)) < 22
                            || 18L + Short.toUnsignedInt(bytes.getShort(16)) > size
                            || Short.toUnsignedInt(bytes.getShort(18)) != bits) throw new IOException("Unsupported extensible WAVE format");
                    channelMask = Integer.toUnsignedLong(bytes.getInt(20));
                    encoding = bytes.getInt(24);
                    byte[] suffix = {0, 0, 16, 0, (byte)128, 0, 0, (byte)170, 0, 56, (byte)155, 113};
                    for (int i = 0; i < suffix.length; i++) if (format[28 + i] != suffix[i]) throw new IOException("Unknown WAVE subformat");
                }
                if (encoding != 1 && encoding != 3 || encoding == 3 && bits != 32) throw new UnsupportedEncodingException("Unsupported WAVE encoding");
                if (bits != 16 && bits != 24 && bits != 32) throw new UnsupportedEncodingException("Unsupported WAVE bit depth");
                PcmChannelLayout.validate(channels, channelMask, false);
                try { found = new PcmOutput(channels, bits, rate, floatSupported, channelMask, multichannelSupported); }
                catch (IllegalArgumentException error) { throw new UnsupportedEncodingException(error.getMessage()); }
                if (alignment != channels * bits / 8 || byteRate != (long) rate * alignment) throw new IOException("Invalid WAVE byte rate");
                floatingInput = encoding == 3;
            } else input.skipNBytes(size);
            long padded = size + (size & 1);
            if (padded > riffRemaining) throw new IOException("Missing WAVE chunk padding");
            if ((size & 1) != 0) input.skipNBytes(1);
            riffRemaining -= padded;
            headerBytes += padded + 8;
        }
        throw new IOException("Too many WAVE chunks");
    }

    @Override public byte[] readChunk(long maxSize) {
        if (closed || remaining == 0 || maxSize <= 0) return null;
        int frames = (int) (Math.min(maxSize, 1_048_576) / output.frameSize());
        if (frames == 0) throw new IllegalArgumentException("Chunk smaller than one PCM frame");
        int count = (int) Math.min(remaining, (long) frames * output.channels * output.bits / 8);
        try {
            byte[] raw = exact(input, count);
            remaining -= count; framesRead += count / (output.channels * output.bits / 8);
            return output.convert(raw, ieeeFloat);
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    static byte[] exact(InputStream input, int size) throws IOException {
        byte[] data = input.readNBytes(size);
        if (data.length != size) throw new EOFException("Truncated PCM stream");
        return data;
    }
    private static String tag(byte[] data, int offset) { return new String(data, offset, 4, StandardCharsets.US_ASCII); }
    private static long unsigned(byte[] data, int offset) { return Integer.toUnsignedLong(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)); }
    @Override public int getFormat() { return output.format(); }
    @Override public int getSampleRate() { return output.sampleRate; }
    @Override public int getFrameSize() { return output.frameSize(); }
    @Override public SampleEncoding getSampleEncoding() { return output.floating ? SampleEncoding.PCM_F32_LE : SampleEncoding.PCM_S16_LE; }
    @Override public long getPositionMillis() { return framesRead * 1000 / output.sampleRate; }
    @Override public void close() { if (!closed) { closed = true; try { input.close(); } catch (IOException ignored) {} } }
}
