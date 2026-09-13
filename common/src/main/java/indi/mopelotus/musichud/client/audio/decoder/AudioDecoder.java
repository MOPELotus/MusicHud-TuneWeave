package indi.mopelotus.musichud.client.audio.decoder;

public interface AudioDecoder extends AutoCloseable {
    enum SampleEncoding { PCM_S16_LE, PCM_S24_LE, PCM_S32_LE, PCM_F32_LE }

    byte[] readChunk(long maxSize);

    default boolean seekToMillis(long positionMillis) {
        return false;
    }

    default long getPositionMillis() {
        return -1L;
    }

    int getFormat();
    int getSampleRate();

    int getFrameSize();

    default SampleEncoding getSampleEncoding() {
        return SampleEncoding.PCM_S16_LE;
    }

    void close();
}
