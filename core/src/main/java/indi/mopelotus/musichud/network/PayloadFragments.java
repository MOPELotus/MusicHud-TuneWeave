package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.ProjectIdentity;
import indi.mopelotus.musichud.network.payloads.*;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.ClientPayloadFragment;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.ServerPayloadFragment;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Shared bounded framing for plugin-message transports and mod transports. */
public final class PayloadFragments {
    public static final int CHUNK_BYTES = 24_000;
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Map<String, Binding<?>> C2S = new ConcurrentHashMap<>(), S2C = new ConcurrentHashMap<>();
    private static final Assembler SERVER = new Assembler(), CLIENT = new Assembler();
    private static volatile Object clientConnection = new Object();
    private PayloadFragments() {}

    public record Frame(UUID id, String channel, int index, int count, int total, byte[] data) {
        public Frame {
            Objects.requireNonNull(id); Objects.requireNonNull(channel); Objects.requireNonNull(data);
            if (!channel.startsWith(ProjectIdentity.MOD_ID + ":") || channel.length() > 128
                    || !channel.matches("[a-z0-9_]+:[a-z0-9_]+") || total <= CHUNK_BYTES || total > MAX_BYTES
                    || count != (total + CHUNK_BYTES - 1) / CHUNK_BYTES || index < 0 || index >= count
                    || data.length != Math.min(CHUNK_BYTES, total - index * CHUNK_BYTES)) throw new IllegalArgumentException("Invalid payload fragment");
            data = data.clone();
        }
        @Override public byte[] data() { return data.clone(); }
    }

    private static final ByteBufCodec<byte[]> DATA = new ByteBufCodec<>() {
        public void encode(io.netty.buffer.ByteBuf buffer, byte[] data) { buffer.writeInt(data.length); buffer.writeBytes(data); }
        public byte[] decode(io.netty.buffer.ByteBuf buffer) {
            int size = buffer.readInt();
            if (size < 0 || size > CHUNK_BYTES || size > buffer.readableBytes()) throw new IllegalArgumentException("Invalid fragment byte length");
            byte[] data = new byte[size]; buffer.readBytes(data); return data;
        }
    };
    public static final ByteBufCodec<Frame> CODEC = ByteBufCodec.composite(Codecs.UUID, Frame::id,
            Codecs.STRING_UTF8, Frame::channel, Codecs.INT, Frame::index, Codecs.INT, Frame::count,
            Codecs.INT, Frame::total, DATA, Frame::data, Frame::new);

    public static <T extends IPayload> void register(Class<T> type, ByteBufCodec<T> codec, NetworkReceiver<T> receiver, boolean serverbound) {
        (serverbound ? C2S : S2C).put(ProtocolChannels.id(type), new Binding<>(codec, receiver));
    }

    public static void sendC2S(C2SPayload payload, Consumer<C2SPayload> send) {
        if (payload instanceof ClientPayloadFragment) { send.accept(payload); return; }
        byte[] encoded = encode(payload, C2S);
        if (encoded.length <= CHUNK_BYTES) send.accept(payload);
        else split(ProtocolChannels.id(payload.getClass()), encoded).forEach(frame -> send.accept(new ClientPayloadFragment(frame)));
    }

    public static void sendS2C(S2CPayload payload, Consumer<S2CPayload> send) {
        if (payload instanceof ServerPayloadFragment) { send.accept(payload); return; }
        byte[] encoded = encode(payload, S2C);
        if (encoded.length <= CHUNK_BYTES) send.accept(payload);
        else split(ProtocolChannels.id(payload.getClass()), encoded).forEach(frame -> send.accept(new ServerPayloadFragment(frame)));
    }

    @SuppressWarnings("unchecked")
    private static byte[] encode(IPayload payload, Map<String, Binding<?>> bindings) {
        var binding = (Binding<IPayload>) bindings.get(ProtocolChannels.id(payload.getClass()));
        if (binding == null) throw new IllegalArgumentException("Unregistered payload");
        var buffer = Unpooled.buffer(256, MAX_BYTES);
        try { binding.codec.encode(buffer, payload); byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return bytes; }
        finally { buffer.release(); }
    }

    public static List<Frame> split(String channel, byte[] bytes) {
        if (bytes.length <= CHUNK_BYTES || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Invalid fragmented payload size");
        UUID id = UUID.randomUUID(); int count = (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        List<Frame> frames = new ArrayList<>(count);
        for (int index = 0; index < count; index++) frames.add(new Frame(id, channel, index, count, bytes.length,
                Arrays.copyOfRange(bytes, index * CHUNK_BYTES, Math.min(bytes.length, (index + 1) * CHUNK_BYTES))));
        return List.copyOf(frames);
    }

    public static void receive(Frame frame, IPlayerClient player, boolean serverbound) {
        if (!player.isConnected()) return;
        Map<String, Binding<?>> bindings = serverbound ? C2S : S2C;
        if (frame.channel.equals(ProtocolChannels.id(ClientPayloadFragment.class))
                || frame.channel.equals(ProtocolChannels.id(ServerPayloadFragment.class)) || !bindings.containsKey(frame.channel))
            throw new IllegalArgumentException("Unknown or nested fragmented payload");
        Object peer = serverbound ? ServerPlayerRegistry.getInstance().sessionToken(player.getUUID()) : clientConnection;
        if (peer == null) return;
        byte[] result = (serverbound ? SERVER : CLIENT).accept(peer, frame, System.currentTimeMillis());
        if (result == null) return;
        if (serverbound && peer != ServerPlayerRegistry.getInstance().sessionToken(player.getUUID())
                || !serverbound && peer != clientConnection) return;
        bindings.get(frame.channel).receive(result, player);
    }

    public static void resetClient() { clientConnection = new Object(); CLIENT.clear(); }
    public static void resetServer() { SERVER.clear(); }
    public static void forgetServerPeer(Object peer) { SERVER.forget(peer); }

    private record Binding<T extends IPayload>(ByteBufCodec<T> codec, NetworkReceiver<T> receiver) {
        void receive(byte[] bytes, IPlayerClient player) {
            var buffer = Unpooled.wrappedBuffer(bytes);
            try {
                T payload = codec.decode(buffer);
                if (buffer.isReadable()) throw new IllegalArgumentException("Trailing reassembled bytes");
                receiver.receive(payload, player);
            } finally { buffer.release(); }
        }
    }

    public static final class Assembler {
        private record Key(Object peer, UUID id) {
            @Override public boolean equals(Object other) { return other instanceof Key key && peer == key.peer && id.equals(key.id); }
            @Override public int hashCode() { return 31 * System.identityHashCode(peer) + id.hashCode(); }
        }
        private static final class Pending {
            final Frame first; final byte[][] pieces; final long started;
            int received;
            Pending(Frame first, long started) { this.first = first; pieces = new byte[first.count][]; this.started = started; }
        }
        private final Map<Key, Pending> pending = new HashMap<>();
        private final LinkedHashMap<Key, Long> completed = new LinkedHashMap<>();
        private int reserved;
        public synchronized void clear() { pending.clear(); completed.clear(); reserved = 0; }
        public synchronized void forget(Object peer) {
            completed.keySet().removeIf(key -> key.peer == peer);
            var iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getKey().peer == peer) { reserved -= entry.getValue().first.total; iterator.remove(); }
            }
        }

        public synchronized byte[] accept(Object peer, Frame frame, long now) {
            Objects.requireNonNull(peer);
            var iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Pending value = iterator.next().getValue();
                if (now < value.started || now - value.started >= 10_000) { reserved -= value.first.total; iterator.remove(); }
            }
            Key key = new Key(peer, frame.id);
            completed.entrySet().removeIf(entry -> now < entry.getValue() || now - entry.getValue() >= 10_000);
            if (completed.containsKey(key)) return null;
            Pending value = pending.get(key);
            if (value == null) {
                long samePeer = pending.keySet().stream().filter(existing -> existing.peer == peer).count();
                if (pending.size() >= 16 || samePeer >= 4 || reserved > 32 * 1024 * 1024 - frame.total)
                    throw new IllegalArgumentException("Too many pending fragments");
                value = new Pending(frame, now); pending.put(key, value); reserved += frame.total;
            }
            if (!value.first.channel.equals(frame.channel) || value.first.total != frame.total || value.first.count != frame.count) {
                pending.remove(key); reserved -= value.first.total; throw new IllegalArgumentException("Conflicting fragment metadata");
            }
            if (value.pieces[frame.index] != null) {
                if (!Arrays.equals(value.pieces[frame.index], frame.data)) {
                    pending.remove(key); reserved -= value.first.total;
                    throw new IllegalArgumentException("Conflicting duplicate fragment");
                }
                return null;
            }
            value.pieces[frame.index] = frame.data.clone();
            if (++value.received != frame.count) return null;
            pending.remove(key); reserved -= value.first.total;
            completed.put(key, now);
            while (completed.size() > 1024) completed.remove(completed.keySet().iterator().next());
            byte[] result = new byte[frame.total];
            for (int i = 0; i < value.pieces.length; i++) System.arraycopy(value.pieces[i], 0, result, i * CHUNK_BYTES, value.pieces[i].length);
            return result;
        }
    }
}
