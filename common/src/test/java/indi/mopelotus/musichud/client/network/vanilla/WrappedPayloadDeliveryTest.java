package indi.mopelotus.musichud.client.network.vanilla;

import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.PlaybackSession;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.network.payloads.IPayload;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.PlaybackResourceFailureMessage;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.SyncCurrentPlayingMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WrappedPayloadDeliveryTest {
    private static final IPlayerClient PLAYER = new IPlayerClient() {
        private final UUID id = UUID.randomUUID();
        public UUID getUUID() { return id; }
        public String getName() { return "listener"; }
        public ClientType getClientType() { return ClientType.REMOTE; }
    };

    @Test
    void decodedClientReportReachesReceiverWithSenderIdentity() {
        var report = new PlaybackResourceFailureMessage(UUID.randomUUID(), 3);
        var packet = roundTrip(PlaybackResourceFailureMessage.CODEC, report);
        List<PlaybackResourceFailureMessage> received = new ArrayList<>();
        packet.receive((message, player) -> {
            assertSame(PLAYER, player);
            received.add(message);
        }, PLAYER);
        assertEquals(List.of(report), received);
    }

    @Test
    void decodedServerSnapshotReachesReceiverWithAuthoritativeSequence() {
        var snapshot = new SyncCurrentPlayingMessage(PlaybackSession.stopped(19), MusicDetail.NONE);
        var packet = roundTrip(SyncCurrentPlayingMessage.CODEC, snapshot);
        List<Long> receivedSequences = new ArrayList<>();
        packet.receive((message, player) -> {
            assertSame(PLAYER, player);
            assertFalse(message.playbackSession().isActive());
            receivedSequences.add(message.playbackSession().sequence());
        }, PLAYER);
        assertEquals(List.of(19L), receivedSequences);
    }

    @Test
    void truncatedClientReportFailsBeforeReceiverIsInvoked() {
        var codec = StreamCodecWrapper.of(PlaybackResourceFailureMessage.CODEC);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        List<IPayload> delivered = new ArrayList<>();
        try {
            codec.encode(buffer, new CustomPacketPayloadWrapper<>(
                    new PlaybackResourceFailureMessage(UUID.randomUUID(), 2)));
            buffer.writerIndex(buffer.writerIndex() - 1);
            assertThrows(IndexOutOfBoundsException.class, () ->
                    codec.decode(buffer).receive((message, player) -> delivered.add(message), PLAYER));
            assertTrue(delivered.isEmpty());
        } finally {
            buffer.release();
        }
    }

    private static <T extends IPayload> CustomPacketPayloadWrapper<T> roundTrip(ByteBufCodec<T> codec, T payload) {
        var streamCodec = StreamCodecWrapper.of(codec);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            streamCodec.encode(buffer, new CustomPacketPayloadWrapper<>(payload));
            var decoded = streamCodec.decode(buffer);
            assertFalse(buffer.isReadable());
            // The transport envelope deliberately does not implement the domain protocol.
            assertFalse(IPayload.class.isInstance(decoded));
            return decoded;
        } finally {
            buffer.release();
        }
    }
}
