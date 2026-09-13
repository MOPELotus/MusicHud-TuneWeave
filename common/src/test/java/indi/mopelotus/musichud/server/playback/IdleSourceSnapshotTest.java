package indi.mopelotus.musichud.server.playback;

import indi.mopelotus.musichud.beans.api.IdlePlaySource;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.beans.user.Profile;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.AddToIdlePlaySourceMessage;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IdleSourceSnapshotTest {
    @Test void sequentialSourceWrapsAndKeepsIndependentCursor() {
        var playlist = playlist();
        playlist.getTracks().add(MusicDetail.fromTuneWeave(2, "netease:2", "track", "Two", 1000, Album.NONE, List.of()));
        var source = new IdlePlaySource(42, Playlist.class, indi.mopelotus.musichud.beans.api.IdlePlayMode.SEQUENTIAL);
        source.useClientCollection(playlist);
        var random = new Random(1);
        assertEquals("netease:1", source.nextTrack(random).orElseThrow().getSourceRef());
        assertEquals("netease:2", source.nextTrack(random).orElseThrow().getSourceRef());
        assertEquals("netease:1", source.nextTrack(random).orElseThrow().getSourceRef());
        var second = new IdlePlaySource(42, Playlist.class, indi.mopelotus.musichud.beans.api.IdlePlayMode.SEQUENTIAL);
        second.useClientCollection(playlist);
        assertEquals("netease:1", second.nextTrack(random).orElseThrow().getSourceRef());
    }

    @Test void modeCodecRejectsInvalidOrdinalAndNullMode() {
        assertThrows(NullPointerException.class, () -> new IdlePlaySource(42, Playlist.class, null));
        var source = new IdlePlaySource(42, Playlist.class, indi.mopelotus.musichud.beans.api.IdlePlayMode.SEQUENTIAL);
        var buffer = Unpooled.buffer();
        try {
            IdlePlaySource.CODEC.encode(buffer, source);
            assertEquals(indi.mopelotus.musichud.beans.api.IdlePlayMode.SEQUENTIAL, IdlePlaySource.CODEC.decode(buffer).getMode());
            buffer.readerIndex(0);
            buffer.setByte(buffer.writerIndex() - 1, 127);
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> IdlePlaySource.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void networkCollectionTypeRejectsArbitraryClassNames() {
        var buffer = Unpooled.buffer();
        try {
            indi.mopelotus.musichud.network.Codecs.STRING_UTF8.encode(buffer, "java.lang.String");
            assertThrows(io.netty.handler.codec.DecoderException.class,
                    () -> indi.mopelotus.musichud.network.Codecs.CLASS.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void persistedModeReferenceRoundTripsAndLegacyDefaultsToRandom() {
        var gson = new com.google.gson.GsonBuilder()
                .registerTypeAdapter(Class.class, new indi.mopelotus.musichud.utils.JsonUtil.ClassAdapter()).create();
        var source = new IdlePlaySource(42, Playlist.class, indi.mopelotus.musichud.beans.api.IdlePlayMode.SEQUENTIAL)
                .withReference("netease:playlist:42");
        var decoded = gson.fromJson(gson.toJson(source), IdlePlaySource.class);
        assertEquals(source.getMode(), decoded.getMode());
        assertEquals(source.getSourceReference(), decoded.getSourceReference());
        String legacy = "{\"id\":42,\"type\":\"indi.mopelotus.musichud.beans.music.Playlist\"}";
        assertEquals(indi.mopelotus.musichud.beans.api.IdlePlayMode.RANDOM, gson.fromJson(legacy, IdlePlaySource.class).getMode());
    }

    @Test void roundTripsClientSnapshotAndServerCopiesOwnership() {
        var playlist = playlist();
        var source = new IdlePlaySource(42, Playlist.class);
        var message = new AddToIdlePlaySourceMessage(source, playlist);
        var buffer = Unpooled.buffer();
        try {
            AddToIdlePlaySourceMessage.CODEC.encode(buffer, message);
            var decoded = AddToIdlePlaySourceMessage.CODEC.decode(buffer);
            assertEquals("netease:1", decoded.collection().getMusicDetails().getFirst().getSourceRef());
            var owner = new PusherInfo(UUID.randomUUID(), "Owner");
            var copy = IdleSourceSnapshotValidator.copy(decoded.collection(), owner);
            assertEquals(owner, copy.getPusherInfo());
            assertEquals(owner, copy.getMusicDetails().getFirst().getPusherInfo());
            decoded.collection().getMusicDetails().clear();
            assertEquals(1, copy.getMusicDetails().size());
        } finally { buffer.release(); }
    }

    @Test void rejectsMismatchedIdentityTypesAndMalformedTrackMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new IdlePlaySource(1, String.class));
        assertThrows(IllegalArgumentException.class, () -> new AddToIdlePlaySourceMessage(new IdlePlaySource(1, Playlist.class), playlist()));
        var playlist = playlist();
        playlist.getTracks().getFirst().setSourceRef("invalid");
        var invalidReference = playlist;
        assertThrows(IllegalArgumentException.class, () -> IdleSourceSnapshotValidator.copy(invalidReference, PusherInfo.EMPTY));
        playlist = playlist();
        playlist.getTracks().getFirst().setSourceKind("credential");
        var invalid = playlist;
        assertThrows(IllegalArgumentException.class, () -> IdleSourceSnapshotValidator.copy(invalid, PusherInfo.EMPTY));
    }

    @Test void mutableOwnerCannotBreakSourceSetMembership() {
        var source = new IdlePlaySource(42, Playlist.class);
        Set<IdlePlaySource> set = new HashSet<>(Set.of(source));
        source.setPusherInfo(new PusherInfo(UUID.randomUUID(), "Owner"));
        assertTrue(set.remove(new IdlePlaySource(42, Playlist.class)));
    }

    private static Playlist playlist() {
        var playlist = Playlist.fromTuneWeave(42, "netease:playlist:42", "Source", "", 1, 0, Profile.ANONYMOUS);
        playlist.getTracks().add(MusicDetail.fromTuneWeave(1, "netease:1", "track", "Track", 1000, Album.NONE, List.of()));
        return playlist;
    }
}
