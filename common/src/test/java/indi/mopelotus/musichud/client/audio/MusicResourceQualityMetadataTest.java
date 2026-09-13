package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.beans.music.Fee;
import indi.mopelotus.musichud.beans.music.FormatType;
import indi.mopelotus.musichud.beans.music.MusicResourceInfo;
import indi.mopelotus.musichud.beans.music.Quality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MusicResourceQualityMetadataTest {
    @Test void rejectsMalformedQualityAndTruncatedWireData() {
        MusicResourceInfo info = new MusicResourceInfo(1, "https://example.invalid/a",
                320_000, 1, FormatType.MP3, "", Fee.UNSET, 1);
        var buffer = io.netty.buffer.Unpooled.buffer();
        try {
            MusicResourceInfo.CODEC.encode(buffer, info);
            int length = buffer.writerIndex();
            buffer.setByte(length - 2, 127);
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> MusicResourceInfo.CODEC.decode(buffer));
            buffer.setByte(length - 2, Quality.NONE.ordinal());
            buffer.readerIndex(0);
            buffer.writerIndex(length - 1);
            assertThrows(IndexOutOfBoundsException.class, () -> MusicResourceInfo.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test
    void storesRequestedAndActualQualitySeparately() {
        MusicResourceInfo info = new MusicResourceInfo(1, "https://example.invalid/a",
                320_000, 1, FormatType.MP3, "", Fee.UNSET, 1);
        info.setQualityMetadata(Quality.HIGHER, Quality.LOSSLESS);
        assertEquals(Quality.HIGHER, info.getRequestedQuality());
        assertEquals(Quality.LOSSLESS, info.getActualQuality());
    }
}
