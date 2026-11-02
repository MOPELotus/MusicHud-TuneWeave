package indi.mopelotus.musichud.companion.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Local, display-only protocol. RGBA8 pixels are premultiplied, with the first row at the top. */
public final class CompanionWire {
    public static final int PORT = 39841;
    public static final int MAX_DIMENSION = 8192;
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAGIC = 0x4D545743; // MTWC
    private static final int VERSION = 1;

    public enum Kind { FRAME, CLEAR, STATUS }

    public record Message(Kind kind, long generation, long sequence, long capturedAtMillis,
                          boolean locked, int width, int height, byte[] rgba) {
        public Message {
            if (kind == null || generation < 0 || sequence < 0 || rgba == null) {
                throw new IllegalArgumentException("Invalid companion message");
            }
            if (kind == Kind.FRAME) {
                if (rgba.length != pixelBytes(width, height)) throw new IllegalArgumentException("Invalid pixel length");
            } else if (width != 0 || height != 0 || rgba.length != 0) {
                throw new IllegalArgumentException("Control message contains pixels");
            }
        }
    }

    private CompanionWire() {}

    public static int pixelBytes(int width, int height) {
        long length = (long) width * height * 4;
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || length > MAX_BYTES) throw new IllegalArgumentException("Invalid companion dimensions");
        return (int) length;
    }

    public static void write(DataOutputStream out, Message message) throws IOException {
        out.writeInt(MAGIC);
        out.writeShort(VERSION);
        out.writeByte(message.kind.ordinal());
        out.writeByte(message.locked ? 1 : 0);
        out.writeLong(message.generation);
        out.writeLong(message.sequence);
        out.writeLong(message.capturedAtMillis);
        out.writeInt(message.width);
        out.writeInt(message.height);
        out.writeInt(message.rgba.length);
        out.write(message.rgba);
    }

    public static Message read(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC || in.readUnsignedShort() != VERSION) throw new IOException("Unsupported companion protocol");
        int kind = in.readUnsignedByte();
        int locked = in.readUnsignedByte();
        long generation = in.readLong();
        long sequence = in.readLong();
        long capturedAtMillis = in.readLong();
        int width = in.readInt();
        int height = in.readInt();
        int length = in.readInt();
        if (kind >= Kind.values().length || locked > 1 || generation < 0 || sequence < 0) {
            throw new IOException("Invalid companion header");
        }
        try {
            int expected = kind == Kind.FRAME.ordinal() ? pixelBytes(width, height) : 0;
            if (length != expected || (kind != Kind.FRAME.ordinal() && (width != 0 || height != 0))) {
                throw new IllegalArgumentException("Invalid companion payload");
            }
            byte[] pixels = new byte[length];
            in.readFully(pixels);
            return new Message(Kind.values()[kind], generation, sequence, capturedAtMillis,
                    locked == 1, width, height, pixels);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid companion message", error);
        }
    }
}
