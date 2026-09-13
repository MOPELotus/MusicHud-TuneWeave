package indi.mopelotus.musichud.client.audio;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTDirectChannels;

/** One source and its buffer pool. Every operation is checked against the owning device epoch. */
final class OpenAlPlaybackDevice implements AutoCloseable {
    interface Driver {
        long context(); long generation(); boolean available();
        boolean floating(); boolean multichannel(); boolean directChannels();
        int error(); int createSource(); int createBuffer();
        void deleteSource(int source); void deleteBuffer(int buffer);
        void sourceInt(int source, int property, int value);
        void sourceFloat(int source, int property, float value);
        int sourceInt(int source, int property);
        float sourceFloat(int source, int property);
        void play(int source); void stop(int source);
        int unqueue(int source); void queue(int source, int buffer);
        void upload(int buffer, int format, byte[] pcm, int sampleRate);
        default <T> T guarded(Supplier<T> operation) { return operation.get(); }
    }
    static final Driver LWJGL = new Driver() {
        public long context() { return ALC10.alcGetCurrentContext(); }
        public long generation() { return SoundEngineEpoch.generation(); }
        public boolean available() { return SoundEngineEpoch.available() && context() != 0; }
        public boolean floating() { return AL.getCapabilities().AL_EXT_FLOAT32; }
        public boolean multichannel() { return AL.getCapabilities().AL_EXT_MCFORMATS; }
        public boolean directChannels() { return AL.getCapabilities().AL_SOFT_direct_channels; }
        public int error() { return AL10.alGetError(); }
        public int createSource() { return AL10.alGenSources(); }
        public int createBuffer() { return AL10.alGenBuffers(); }
        public void deleteSource(int source) { AL10.alDeleteSources(source); }
        public void deleteBuffer(int buffer) { AL10.alDeleteBuffers(buffer); }
        public void sourceInt(int source, int property, int value) { AL10.alSourcei(source, property, value); }
        public void sourceFloat(int source, int property, float value) { AL10.alSourcef(source, property, value); }
        public int sourceInt(int source, int property) { return AL10.alGetSourcei(source, property); }
        public float sourceFloat(int source, int property) { return AL10.alGetSourcef(source, property); }
        public void play(int source) { AL10.alSourcePlay(source); }
        public void stop(int source) { AL10.alSourceStop(source); }
        public int unqueue(int source) { return AL10.alSourceUnqueueBuffers(source); }
        public void queue(int source, int buffer) { AL10.alSourceQueueBuffers(source, buffer); }
        public void upload(int buffer, int format, byte[] pcm, int sampleRate) {
            ByteBuffer direct = ByteBuffer.allocateDirect(pcm.length);
            direct.put(pcm).flip();
            AL10.alBufferData(buffer, format, direct, sampleRate);
        }
        public <T> T guarded(Supplier<T> operation) { return SoundEngineEpoch.guarded(operation); }
    };

    private final Driver driver;
    private final long context;
    private final long generation;
    private final ArrayList<Integer> buffers = new ArrayList<>();
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private int source;
    private OwnedAudioSources.Lease lease;
    private boolean closed;
    private int format = -1;
    private int sampleRate = -1;

    OpenAlPlaybackDevice(Driver driver, int capacity) {
        this.driver = driver;
        context = driver.context(); generation = driver.generation();
        try {
            call("alGenSources", () -> source = driver.createSource());
            lease = OwnedAudioSources.INSTANCE.register(context, source);
            for (int i = 0; i < capacity; i++) {
                call("alGenBuffers", () -> {
                    int buffer = driver.createBuffer();
                    if (buffer > 0) { buffers.add(buffer); free.add(buffer); }
                    return buffer;
                });
            }
            run("source relative", () -> driver.sourceInt(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE));
            run("source rolloff", () -> driver.sourceFloat(source, AL10.AL_ROLLOFF_FACTOR, 0));
        } catch (RuntimeException failure) { close(); throw failure; }
    }
    long context() { return context; }
    long generation() { return generation; }
    boolean floating() { return call("float capability", driver::floating); }
    boolean multichannel() { return call("multichannel capability", driver::multichannel); }
    boolean current() { return !closed && driver.available() && context != 0
            && context == driver.context() && generation == driver.generation(); }
    boolean hasSpace() { return !free.isEmpty(); }
    int nextBuffer() { return free.getFirst(); }
    int format() { return format; }
    boolean matches(int format, int sampleRate) { return this.format == -1 || this.format == format && this.sampleRate == sampleRate; }

    void queue(int buffer, int format, byte[] pcm, int sampleRate) {
        if (!matches(format, sampleRate) || free.peekFirst() == null || free.peekFirst() != buffer)
            throw new IllegalStateException("Device PCM queue changed format or buffer ownership");
        if (pcm.length == 0 || pcm.length % OpenAlFormatSelector.frameSize(format) != 0)
            throw new IllegalArgumentException("Partial device PCM frame");
        this.format = format; this.sampleRate = sampleRate;
        if (call("direct channels capability", driver::directChannels))
            run("direct channels", () -> driver.sourceInt(source, SOFTDirectChannels.AL_DIRECT_CHANNELS_SOFT,
                    OpenAlFormatSelector.directChannels(format) ? AL10.AL_TRUE : AL10.AL_FALSE));
        run("alBufferData", () -> driver.upload(buffer, format, pcm, sampleRate));
        run("alSourceQueueBuffers", () -> driver.queue(source, buffer));
        free.removeFirst();
    }
    List<Integer> processed() {
        int count = call("processed buffers", () -> driver.sourceInt(source, AL10.AL_BUFFERS_PROCESSED));
        if (count < 0 || count > buffers.size() - free.size())
            throw new OpenAlFailure(OpenAlFailure.Kind.DEVICE_LOST, "processed count", 0, context, generation);
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int buffer = call("alSourceUnqueueBuffers", () -> driver.unqueue(source));
            if (!buffers.contains(buffer) || free.contains(buffer))
                throw new OpenAlFailure(OpenAlFailure.Kind.DEVICE_LOST, "buffer identity", 0, context, generation);
            result.add(buffer); free.addLast(buffer);
        }
        return result;
    }
    float offsetSeconds() { return call("sample offset", () -> driver.sourceFloat(source, AL11.AL_SEC_OFFSET)); }
    int queued() { return call("queued buffers", () -> driver.sourceInt(source, AL10.AL_BUFFERS_QUEUED)); }
    void play(float gain) {
        run("gain", () -> driver.sourceFloat(source, AL10.AL_GAIN, gain));
        if (call("source state", () -> driver.sourceInt(source, AL10.AL_SOURCE_STATE)) != AL10.AL_PLAYING)
            run("alSourcePlay", () -> driver.play(source));
    }
    private void run(String operation, Runnable action) { call(operation, () -> { action.run(); return null; }); }
    private <T> T call(String operation, Supplier<T> action) {
        return driver.guarded(() -> {
            if (!current()) throw new OpenAlFailure(OpenAlFailure.Kind.DEVICE_LOST, operation, 0, context, generation);
            driver.error(); // Discard an older error before attributing this operation's result.
            T result = action.get();
            int error = driver.error();
            if (!current() || error == AL10.AL_INVALID_NAME)
                throw new OpenAlFailure(OpenAlFailure.Kind.DEVICE_LOST, operation, error, context, generation);
            if (error != AL10.AL_NO_ERROR)
                throw new OpenAlFailure(OpenAlFailure.Kind.OPERATION, operation, error, context, generation);
            return result;
        });
    }
    @Override public void close() {
        if (closed) return;
        try {
            driver.guarded(() -> {
                if (current()) {
                    if (source != 0) {
                        quietly(() -> driver.stop(source));
                        quietly(() -> driver.deleteSource(source));
                    }
                    for (int buffer : buffers) quietly(() -> driver.deleteBuffer(buffer));
                }
                return null;
            });
        } finally {
            closed = true;
            OwnedAudioSources.INSTANCE.release(lease);
            lease = null;
            free.clear(); buffers.clear();
        }
    }
    private void quietly(Runnable cleanup) {
        try { driver.error(); cleanup.run(); driver.error(); }
        catch (RuntimeException ignored) { /* An already-lost device cannot retain owned live AL resources. */ }
    }
}
