package indi.mopelotus.musichud.client.utils.lyrics;

import indi.mopelotus.musichud.beans.music.Lyric;
import indi.mopelotus.musichud.beans.music.LyricInfo;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WordByWordLyricParserTest {
    @Test
    void parsesQqPostfixTimestampsWithoutDroppingFirstCharacter() {
        WordByWordLyricParser.LyricLineMetaData line = parseSingleLine(
                "[25486,4481]爱(25486,400)像(25886,385)是(26271,495)一(26766,465)场(27231,448)小(27679,631)雨(28310,1657)");

        assertEquals("爱像是一场小雨", line.lyric());
        assertEquals(1, line.phraseEndingOffsetMap().get(Duration.ofMillis(25886)));
        assertEquals(4, line.phraseEndingOffsetMap().get(Duration.ofMillis(27231)));
        assertEquals(7, line.phraseEndingOffsetMap().get(Duration.ofMillis(29967)));
    }

    @Test
    void keepsNeteaseYrcPrefixTimestamps() {
        WordByWordLyricParser.LyricLineMetaData line = parseSingleLine(
                "[21590,1190](21590,380,0)素(21970,230,0)胚(22200,280,0)勾(22480,300,0)勒");

        assertEquals("素胚勾勒", line.lyric());
        assertEquals(1, line.phraseEndingOffsetMap().get(Duration.ofMillis(21970)));
        assertEquals(4, line.phraseEndingOffsetMap().get(Duration.ofMillis(22780)));
    }

    @Test
    void keepsTwoFieldPrefixTimestamps() {
        WordByWordLyricParser.LyricLineMetaData line = parseSingleLine(
                "[0,1000](0,500)逐字(500,500)歌词");

        assertEquals("逐字歌词", line.lyric());
        assertEquals(2, line.phraseEndingOffsetMap().get(Duration.ofMillis(500)));
        assertEquals(4, line.phraseEndingOffsetMap().get(Duration.ofMillis(1000)));
    }

    @Test
    void expandsMultipleLineTimestampsAndShiftsPhraseTiming() {
        List<WordByWordLyricParser.LyricLineMetaData> lines = parseLines(
                "[0,1000][2000,1000](0,500)逐字(500,500)歌词");

        assertEquals(2, lines.size());
        assertEquals(Duration.ZERO, lines.get(0).startTime());
        assertEquals(Duration.ofMillis(2000), lines.get(1).startTime());
        assertEquals("逐字歌词", lines.get(1).lyric());
        assertEquals(2, lines.get(1).phraseEndingOffsetMap().get(Duration.ofMillis(2500)));
        assertEquals(4, lines.get(1).phraseEndingOffsetMap().get(Duration.ofMillis(3000)));
    }

    @Test
    void attachesQqLrcTranslationToNearbyQrcLinesAndIgnoresPlaceholders() {
        MusicDetail detail = MusicDetail.fromTuneWeave(1, "qq:004Nn9kj2qndCo", "track", "DAMIDAMI", 180000, null, List.of());
        detail.setLyricInfo(new LyricInfo(
                new Lyric(""),
                new Lyric("[ti:DAMIDAMI]\n[00:00.00]//\n[00:07.50]明月光，夜夜亮\n[00:09.05]让我来看看：有谁睡得不香？"),
                new Lyric("[7505,1546](7505,244)Moon's up high\n[9051,1823](9051,449)Counting sheep"),
                new Lyric("")
        ));

        List<LyricLine> lines = WordByWordLyricParser.parse(detail).stream()
                .filter(line -> line.getType() == LyricLine.Type.NORMAL)
                .toList();
        assertEquals(2, lines.size());
        assertEquals("Moon's up high", lines.get(0).getText());
        assertEquals("明月光，夜夜亮", lines.get(0).getTranslatedText());
        assertEquals("Counting sheep", lines.get(1).getText());
        assertEquals("让我来看看：有谁睡得不香？", lines.get(1).getTranslatedText());
    }

    private static WordByWordLyricParser.LyricLineMetaData parseSingleLine(String lyric) {
        List<WordByWordLyricParser.LyricLineMetaData> lines = parseLines(lyric);
        assertEquals(1, lines.size());
        return lines.getFirst();
    }

    private static List<WordByWordLyricParser.LyricLineMetaData> parseLines(String lyric) {
        List<WordByWordLyricParser.LyricLineMetaData> lines = new ArrayList<>();
        WordByWordLyricParser.matchLine(lyric, line -> {
            if (line.type() == LyricLine.Type.NORMAL) {
                lines.add(line);
            }
        });
        return lines;
    }
}
