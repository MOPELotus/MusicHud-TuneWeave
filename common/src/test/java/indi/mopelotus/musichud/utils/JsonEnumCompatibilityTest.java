package indi.mopelotus.musichud.utils;

import com.google.gson.JsonParseException;
import indi.mopelotus.musichud.beans.api.IdlePlayMode;
import indi.mopelotus.musichud.beans.api.IdlePlaySource;
import indi.mopelotus.musichud.beans.music.Fee;
import indi.mopelotus.musichud.beans.music.FormatType;
import indi.mopelotus.musichud.beans.music.MusicResourceInfo;
import indi.mopelotus.musichud.beans.music.Quality;
import indi.mopelotus.musichud.network.VanillaVarInt;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonEnumCompatibilityTest {
    @Test void unknownStringsPreserveThePayloadAndUseDtoDefaults() {
        var info = JsonUtil.gson.fromJson("""
                {"id":42,"url":"https://example.invalid/audio","br":320000,
                 "type":"future-format","fee":"future-fee",
                 "requestedQuality":"future-quality","actualQuality":null}
                """, MusicResourceInfo.class);
        assertEquals(42, info.getId());
        assertEquals(320000, info.getBitrate());
        assertEquals("https://example.invalid/audio", info.getUrl());
        assertEquals(FormatType.AUTO, info.getType());
        assertEquals(Fee.UNSET, info.getFee());
        assertEquals(Quality.NONE, info.getRequestedQuality());
        assertEquals(Quality.NONE, info.getActualQuality());
        var buffer = Unpooled.buffer();
        try {
            MusicResourceInfo.CODEC.encode(buffer, info);
            assertEquals(Quality.NONE, MusicResourceInfo.CODEC.decode(buffer).getRequestedQuality());
        } finally { buffer.release(); }

        var idle = JsonUtil.gson.fromJson("""
                {"id":42,"type":"indi.mopelotus.musichud.beans.music.Playlist","mode":"future-mode"}
                """, IdlePlaySource.class);
        assertEquals(42, idle.getId());
        assertEquals(IdlePlayMode.RANDOM, idle.getMode());
    }

    @Test void aliasesNamesCodesAndNullKeepTheirExistingMeaning() {
        assertEquals(Quality.EX_HIGH, JsonUtil.gson.fromJson("\"exhigh\"", Quality.class));
        assertEquals(Quality.EX_HIGH, JsonUtil.gson.fromJson("\"ex_high\"", Quality.class));
        assertEquals(Fee.SEPARATELY_PURCHASE, JsonUtil.gson.fromJson("4", Fee.class));
        assertEquals(Fee.UNSET, JsonUtil.gson.fromJson("-1", Fee.class));
        assertNull(JsonUtil.gson.fromJson("null", Quality.class));
        assertNull(JsonUtil.gson.fromJson("\"future-quality\"", Quality.class));
        assertEquals("\"exhigh\"", JsonUtil.gson.toJson(Quality.EX_HIGH));
        assertEquals("4", JsonUtil.gson.toJson(Fee.SEPARATELY_PURCHASE));
    }

    @Test void malformedStructuresAndNumbersAreStillRejected() {
        for (String json : new String[]{"{}", "[]", "true"}) {
            assertThrows(JsonParseException.class, () -> JsonUtil.gson.fromJson(json, Quality.class), json);
        }
        for (String json : new String[]{"1.5", "2147483648"}) {
            assertThrows(NumberFormatException.class, () -> JsonUtil.gson.fromJson(json, Quality.class), json);
        }
    }

    @Test void networkEnumsRemainStrictDespiteJsonCompatibility() {
        for (int ordinal : new int[]{-1, Quality.values().length, Integer.MAX_VALUE}) {
            var buffer = Unpooled.buffer();
            try {
                VanillaVarInt.write(buffer, ordinal);
                assertThrows(DecoderException.class, () -> Quality.CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
    }
}
