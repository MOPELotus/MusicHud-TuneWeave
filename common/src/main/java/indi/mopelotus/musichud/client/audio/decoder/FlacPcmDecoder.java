package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.client.audio.decoder.flac.decode.FrameDecoder;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Native-depth FLAC samples, with bounded metadata and frame-aligned output chunks. */
final class FlacPcmDecoder implements AudioDecoder {
    private final InputStream input;
    private final FrameDecoder decoder;
    private final StreamingFlacInput bitInput;
    private final int[][] channels;
    private final int maxBlockSize;
    private long frameIndex;
    private final PcmOutput output;
    private final long totalSamples;
    private long deliveredFrames, decodedFrames;
    private byte[] pending = new byte[0];
    private int pendingOffset;
    private volatile boolean closed;

    FlacPcmDecoder(InputStream input, boolean floatSupported) throws IOException {
        this(input, floatSupported, false);
    }

    FlacPcmDecoder(InputStream input, boolean floatSupported, boolean multichannelSupported) throws IOException {
        this.input = input;
        byte[] magic = WavPcmDecoder.exact(input, 4);
        if (!Arrays.equals(magic, new byte[]{'f','L','a','C'})) throw new IOException("Invalid FLAC signature");
        byte[] header = WavPcmDecoder.exact(input, 4);
        if ((header[0] & 127) != 0 || metadataLength(header) != 34) throw new IOException("Missing FLAC STREAMINFO");
        byte[] info = WavPcmDecoder.exact(input, 34);
        long metadataBytes = 34;
        Long channelMask = null;
        boolean commentsSeen = false;
        for (int blocks = 0; (header[0] & 128) == 0; blocks++) {
            if (blocks >= 128) throw new IOException("Too many FLAC metadata blocks");
            header = WavPcmDecoder.exact(input, 4);
            int length = metadataLength(header);
            metadataBytes += length;
            if (metadataBytes > 32 * 1024 * 1024 || (header[0] & 127) == 0 || (header[0] & 127) == 127)
                throw new IOException("Invalid FLAC metadata");
            if ((header[0] & 127) == 4) {
                if (commentsSeen || length > 1_048_576) throw new IOException("Invalid or oversized FLAC comments");
                commentsSeen = true;
                channelMask = PcmChannelLayout.vorbisMask(WavPcmDecoder.exact(input, length));
            } else input.skipNBytes(length);
        }
        ByteBuffer stream = ByteBuffer.wrap(info).order(ByteOrder.BIG_ENDIAN);
        int minBlockSize = Short.toUnsignedInt(stream.getShort(0));
        maxBlockSize = Short.toUnsignedInt(stream.getShort(2));
        long packed = stream.getLong(10);
        int sampleRate = (int)(packed >>> 44);
        int channelCount = (int)(packed >>> 41 & 7) + 1;
        int sampleDepth = (int)(packed >>> 36 & 31) + 1;
        totalSamples = packed & 0xfffffffffL;
        if (minBlockSize < 16 || maxBlockSize < minBlockSize) throw new IOException("Invalid FLAC block size");
        long mask = channelMask == null ? PcmChannelLayout.flacDefault(channelCount) : channelMask;
        PcmChannelLayout.validate(channelCount, mask, true);
        try { output = new PcmOutput(channelCount, sampleDepth, sampleRate, floatSupported, mask, multichannelSupported); }
        catch (IllegalArgumentException error) { throw new UnsupportedEncodingException(error.getMessage()); }
        bitInput = new StreamingFlacInput(input);
        decoder = new FrameDecoder(bitInput, sampleDepth);
        // Header validation in FrameDecoder occurs before writing; allocate at its advertised maximum.
        channels = new int[channelCount][65536];
    }

    @Override public byte[] readChunk(long maxSize) {
        if (closed || maxSize <= 0) return null;
        int size = (int) Math.min(maxSize, 1_048_576);
        size -= size % output.frameSize();
        if (size == 0) throw new IllegalArgumentException("Chunk smaller than one PCM frame");
        byte[] result = new byte[size];
        int written = 0;
        try {
            while (written < size) {
                if (pendingOffset == pending.length) {
                    // STREAMINFO bounds the audio payload. CDN files can contain trailing
                    // tags/padding, which must not be interpreted as another audio frame.
                    if (totalSamples != 0 && decodedFrames == totalSamples) break;
                    bitInput.beginFrame();
                    var header = decoder.readFrame(channels, 0);
                    if (header == null) {
                        if (totalSamples != 0 && decodedFrames != totalSamples) throw new EOFException("Truncated FLAC samples");
                        break;
                    }
                    if (header.numChannels != output.channels || header.sampleDepth != -1 && header.sampleDepth != output.bits
                            || header.sampleRate != -1 && header.sampleRate != output.sampleRate
                            || header.blockSize < 1 || header.blockSize > maxBlockSize || header.frameSize > 0xffffff
                            || header.frameIndex >= 0 && header.frameIndex != frameIndex
                            || header.sampleOffset >= 0 && header.sampleOffset != decodedFrames)
                        throw new IOException("Invalid FLAC frame format or sequence");
                    frameIndex++;
                    decodedFrames += header.blockSize;
                    if (totalSamples != 0 && decodedFrames > totalSamples) throw new IOException("FLAC exceeds declared samples");
                    ByteBuffer data = ByteBuffer.allocate(header.blockSize * output.frameSize()).order(ByteOrder.LITTLE_ENDIAN);
                    int[] samples = new int[output.channels];
                    double[] scratch = new double[output.channels];
                    for (int sample = 0; sample < header.blockSize; sample++) {
                        for (int channel = 0; channel < output.channels; channel++) samples[channel] = channels[channel][sample];
                        output.putFrame(data, samples, scratch);
                    }
                    pending = data.array(); pendingOffset = 0;
                }
                int copy = Math.min(size - written, pending.length - pendingOffset);
                System.arraycopy(pending, pendingOffset, result, written, copy);
                pendingOffset += copy; written += copy;
            }
        } catch (IOException error) { throw new UncheckedIOException(error); }
        catch (RuntimeException error) { throw new UncheckedIOException(new IOException("Invalid FLAC frame", error)); }
        deliveredFrames += written / output.frameSize();
        return written == 0 ? null : Arrays.copyOf(result, written);
    }

    private static int metadataLength(byte[] header) { return (header[1] & 255) << 16 | (header[2] & 255) << 8 | header[3] & 255; }
    @Override public int getFormat() { return output.format(); }
    @Override public int getSampleRate() { return output.sampleRate; }
    @Override public int getFrameSize() { return output.frameSize(); }
    @Override public SampleEncoding getSampleEncoding() { return output.floating ? SampleEncoding.PCM_F32_LE : SampleEncoding.PCM_S16_LE; }
    @Override public long getPositionMillis() { return deliveredFrames * 1000 / output.sampleRate; }
    @Override public void close() { if (!closed) { closed = true; try { bitInput.close(); } catch (IOException ignored) {} } }
}
