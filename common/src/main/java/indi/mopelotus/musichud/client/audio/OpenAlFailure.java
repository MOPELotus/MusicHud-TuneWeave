package indi.mopelotus.musichud.client.audio;

/** Typed failures keep context loss and unrelated AL errors out of format fallback. */
final class OpenAlFailure extends RuntimeException {
    enum Kind { DEVICE_LOST, OPERATION }
    private final Kind kind;
    private final String operation;
    private final int code;
    private final long context;
    private final long deviceGeneration;

    OpenAlFailure(Kind kind, String operation, int code, long context, long deviceGeneration) {
        super(operation + " failed (OpenAL " + code + ", " + kind + ")");
        this.kind = kind;
        this.operation = operation;
        this.code = code;
        this.context = context;
        this.deviceGeneration = deviceGeneration;
    }
    boolean deviceLost() { return kind == Kind.DEVICE_LOST; }
    boolean formatRejection() {
        // Out of memory and invalid source names do not establish an unsupported PCM format.
        return kind == Kind.OPERATION && operation.equals("alBufferData")
                && (code == 0xA002 || code == 0xA003);
    }
    long context() { return context; }
    long deviceGeneration() { return deviceGeneration; }
}
