package indi.mopelotus.musichud.client.utils.lyrics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.LyricInfo;
import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.ui.dto.MetaInfoLine;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WordByWordLyricParser {
    private static final Pattern mainPattern = Pattern.compile("((?:\\[[0-9]+,[0-9]+])+)(.*)");
    private static final Pattern timestampPattern = Pattern.compile("\\[([0-9]+),([0-9]+)]");
    /** Accept both NetEase YRC (three numeric fields) and QQ QRC (two fields). */
    private static final Pattern phraseTimestampPattern = Pattern.compile(
            "\\((\\d+),(\\d+)(?:,\\d+)?\\)");
    private static final Duration emptyLineIgnoreDuration = Duration.ofSeconds(5);
    private static final Logger logger = MusicHud.getLogger(FullLineLyricParser.class);

    public static ArrayDeque<LyricLine> parse(MusicDetail musicDetail) {
        LyricInfo lyricInfo = musicDetail.getLyricInfo();
        String lyric = lyricInfo.getWordByWordLyric().getLyric();
        ArrayDeque<LyricLine> voiceTranscript = parseVoiceTranscript(lyric);
        if (voiceTranscript != null) {
            return voiceTranscript;
        }
        String translatedLyric = lyricInfo.getWordByWordTranslatedLyric().getLyric();
        if (translatedLyric == null || translatedLyric.isBlank()) {
            translatedLyric = lyricInfo.getTranslatedLyric().getLyric();
        }
        LinkedHashMap<Duration, LyricLine> map = new LinkedHashMap<>();
        List<LyricLine> lyricLinesWithoutValidTimestamp = new ArrayList<>(0);
        matchLine(lyric, (metaData) -> {
            Duration startTime = metaData.startTime();
            LyricLine lyricLine = map.get(startTime);
            String lyricString = metaData.lyric() == null ? "" : metaData.lyric();
            lyricString = lyricString.replace('\n', ' ').trim();
            if (lyricLine == null) {
                // Remove duration for smooth transition between lines
                lyricLine = LyricLine.builder()
                        .startTime(startTime)
                        .text(lyricString)
                        .type(metaData.type()).build();
                if (startTime == null && lyricLine.getText() != null && !lyricLine.getText().startsWith("}")) {
                    lyricLinesWithoutValidTimestamp.add(lyricLine);
                }
            } else if (!lyricString.isEmpty()) {
                lyricLine.setText(lyricLine.getText() + "\n" + lyricString);
            }
            if (metaData.phraseEndingOffsetMap != null) {
                lyricLine.getPhraseEndingOffsetMap().putAll(metaData.phraseEndingOffsetMap);
                lyricLine.setWordByWord(true);
            }
            map.put(startTime, lyricLine);
        });
        attachTranslations(map, lyricLinesWithoutValidTimestamp, translatedLyric);
        ArrayDeque<LyricLine> lyricLines = new ArrayDeque<>(lyricLinesWithoutValidTimestamp);
        lyricLines.addAll(map.values());
        List<LyricLine> list = lyricLines.stream().sorted(Comparator.comparing(LyricLine::getStartTime)).toList();
        lyricLines.clear();
        int nextIndex = 1;
        LyricLine lastLyricLine = null;
        LyricLine firstNormalLyricLine = null;
        for (LyricLine lyricLine : list) {
            if (firstNormalLyricLine == null && lyricLine.getType() == LyricLine.Type.NORMAL) {
                firstNormalLyricLine = lyricLine;
                if (lastLyricLine == null || lastLyricLine.getType() == LyricLine.Type.META_DATA) {
                    Duration oneSec = Duration.ofSeconds(1);
                    Duration rhythmStartTime = lastLyricLine == null ? oneSec : lastLyricLine.getStartTime().plus(oneSec);
                    Duration rhythmDuration = lyricLine.getStartTime().minus(rhythmStartTime);
                    if (rhythmDuration.compareTo(emptyLineIgnoreDuration) > 0) {
                        if (lastLyricLine != null) {
                            lastLyricLine.setDuration(oneSec);
                        }
                        LyricLine rhythmLine = LyricLine.builder()
                                .startTime(rhythmStartTime)
                                .previous(lastLyricLine)
                                .type(LyricLine.Type.RHYTHM)
                                .text("")
                                .build();
                        lastLyricLine = rhythmLine;
                        lyricLines.add(rhythmLine);
                    }
                }
            }
            if (lastLyricLine != null) {
                if (lastLyricLine.getDuration() == null) {
                    lastLyricLine.setDuration(lyricLine.getStartTime().minus(lastLyricLine.getStartTime()));
                }
                lastLyricLine.setNext(lyricLine);
                lyricLine.setPrevious(lastLyricLine);
            }
            lastLyricLine = lyricLine;
            String text = lyricLine.getText();
            if (text == null || text.isEmpty()) {
                String translatedText = lyricLine.getTranslatedText();
                if (translatedText == null || translatedText.isEmpty()) {
                    if (nextIndex < list.size()) {
                        Duration minus = list.get(nextIndex).getStartTime().minus(lyricLine.getStartTime());
                        if (minus.compareTo(emptyLineIgnoreDuration) > 0) {
                            lyricLine.setType(LyricLine.Type.RHYTHM);
                            lyricLine.setText("");
                            lyricLines.add(lyricLine);
                        } else {
                            logger.debug("An empty lyric line is ignored due to its duration ({} s)", minus.toSeconds());
                        }
                    } else {
                        logger.debug("An empty lyric line is ignored due to its position (last one)");
                    }
                }
            } else {
                lyricLines.add(lyricLine);
            }
            nextIndex += 1;
        }
        if (lastLyricLine != null && lastLyricLine.getDuration() == null) {
            lastLyricLine.setDuration(Duration.ofMillis(musicDetail.getDurationMillis()).minus(lastLyricLine.getStartTime()));
        }
        return lyricLines;
    }


    private static void attachTranslations(Map<Duration, LyricLine> map,
                                           List<LyricLine> lyricLinesWithoutValidTimestamp,
                                           String translatedLyric) {
        if (translatedLyric == null || translatedLyric.isBlank()) return;
        List<LyricLineMetaData> translations = new ArrayList<>();
        matchLine(translatedLyric, metaData -> {
            if (metaData.type() == LyricLine.Type.NORMAL) translations.add(metaData);
        });
        if (translations.isEmpty()) {
            FullLineLyricParser.matchLine(translatedLyric, metaData -> {
                if (metaData.type() == LyricLine.Type.NORMAL) {
                    translations.add(new LyricLineMetaData(metaData.startTime(), null, metaData.lyric(), metaData.type(), null));
                }
            });
        }
        for (LyricLineMetaData metaData : translations) {
            String text = metaData.lyric() == null ? "" : metaData.lyric().replace('\u00A0', ' ').replace('\n', ' ').trim();
            if (LyricTiming.isPlaceholderTranslation(text)) continue;
            Duration startTime = metaData.startTime();
            Duration matched = startTime == null ? null
                    : LyricTiming.nearest(startTime, map.keySet(), LyricTiming.TRANSLATION_MATCH_TOLERANCE_MILLIS);
            LyricLine lyricLine = matched == null ? null : map.get(matched);
            if (lyricLine == null) {
                lyricLine = LyricLine.builder().startTime(startTime).build();
                if (startTime != null) {
                    map.put(startTime, lyricLine);
                } else {
                    lyricLinesWithoutValidTimestamp.add(lyricLine);
                }
            }
            lyricLine.setTranslatedText(text);
        }
    }
    private static ArrayDeque<LyricLine> parseVoiceTranscript(String lyric) {
        if (lyric == null || !lyric.stripLeading().startsWith("{")) return null;
        try {
            JsonObject document = JsonParser.parseString(lyric).getAsJsonObject();
            JsonElement sentencesValue = document.get("sents");
            if (sentencesValue == null || !sentencesValue.isJsonArray()) return null;
            ArrayDeque<LyricLine> result = new ArrayDeque<>();
            LyricLine previous = null;
            for (JsonElement value : sentencesValue.getAsJsonArray()) {
                if (!value.isJsonObject()) continue;
                JsonObject sentence = value.getAsJsonObject();
                if (!sentence.has("beg") || !sentence.has("name")) continue;
                long begin = sentence.get("beg").getAsLong();
                long end = sentence.has("end") && !sentence.get("end").isJsonNull()
                        ? sentence.get("end").getAsLong() : begin;
                LyricLine line = LyricLine.builder()
                        .startTime(Duration.ofMillis(Math.max(0, begin)))
                        .duration(Duration.ofMillis(Math.max(0, end - begin)))
                        .text(sentence.get("name").getAsString().strip())
                        .type(LyricLine.Type.NORMAL)
                        .build();
                if (line.getText().isEmpty()) continue;
                line.setPrevious(previous);
                if (previous != null) previous.setNext(line);
                result.add(line);
                previous = line;
            }
            return result.isEmpty() ? null : result;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static void matchLine(String lyric, Consumer<LyricLineMetaData> matchedConsumer) {
        List<MetaInfoLine> metaInfoLines = RegexJsonExtractor.extractJsonObjectsSafely(lyric, MetaInfoLine.class);
        metaInfoLines.forEach(metaInfoLine -> {
            matchedConsumer.accept(new LyricLineMetaData(metaInfoLine.getTimestampDuration(), null, metaInfoLine.getText(), LyricLine.Type.META_DATA, null));
        });
        Matcher lineMatcher = mainPattern.matcher(lyric);
        Duration lastLineEnd = Duration.ZERO;
        while (lineMatcher.find()) {
            String timestampGroups = lineMatcher.group(1);
            String lineRawText = lineMatcher.group(2);
            Matcher timestampMatcher = timestampPattern.matcher(timestampGroups);
            long sourceLineStartMillis = -1;
            while (timestampMatcher.find()) {
                long lineStartMillis = Long.parseLong(timestampMatcher.group(1));
                if (sourceLineStartMillis < 0) {
                    sourceLineStartMillis = lineStartMillis;
                }
                Duration lineStart = Duration.ofMillis(lineStartMillis);

                Duration interval = lineStart.minus(lastLineEnd);
                if (interval.compareTo(emptyLineIgnoreDuration) > 0) {
                    matchedConsumer.accept(new LyricLineMetaData(lastLineEnd, interval, "", LyricLine.Type.RHYTHM, null));
                }

                Matcher phraseMatcher = phraseTimestampPattern.matcher(lineRawText);
                List<PhraseTimestamp> timestamps = new ArrayList<>();
                while (phraseMatcher.find()) {
                    timestamps.add(new PhraseTimestamp(
                            phraseMatcher.start(), phraseMatcher.end(),
                            Long.parseLong(phraseMatcher.group(1)),
                            Long.parseLong(phraseMatcher.group(2))));
                }
                Map<Duration, Integer> phrases = new LinkedHashMap<>();
                StringBuilder lineText = new StringBuilder();
                Duration lastPhraseEnd = lineStart;
                if (!timestamps.isEmpty()) {
                    boolean timestampsFollowText = timestamps.getFirst().start() > 0;
                    for (int index = 0; index < timestamps.size(); index++) {
                        PhraseTimestamp timestamp = timestamps.get(index);
                        int textStart;
                        int textEnd;
                        if (timestampsFollowText) {
                            textStart = index == 0 ? 0 : timestamps.get(index - 1).end();
                            textEnd = timestamp.start();
                        } else {
                            textStart = timestamp.end();
                            textEnd = index + 1 < timestamps.size()
                                    ? timestamps.get(index + 1).start() : lineRawText.length();
                        }
                        String phraseText = normalizePhraseText(lineRawText.substring(textStart, textEnd));
                        lineText.append(phraseText);
                        long relativePhraseEndMillis = timestamp.startMillis()
                                + timestamp.durationMillis() - sourceLineStartMillis;
                        Duration phraseEnd = lineStart.plusMillis(relativePhraseEndMillis);
                        phrases.put(phraseEnd, lineText.length());
                        if (phraseEnd.compareTo(lastPhraseEnd) > 0) {
                            lastPhraseEnd = phraseEnd;
                        }
                    }
                }
                Duration lineDuration = Duration.ofMillis(Long.parseLong(timestampMatcher.group(2)));
                Duration phraseDuration = lastPhraseEnd.minus(lineStart);
                if (!timestamps.isEmpty() && phraseDuration.compareTo(lineDuration) < 0) {
                    lineDuration = phraseDuration;
                }
                matchedConsumer.accept(new LyricLineMetaData(lineStart, lineDuration, lineText.toString(), LyricLine.Type.NORMAL, phrases));
                lastLineEnd = lineStart.plus(lineDuration);
            }
        }
    }

    private static String normalizePhraseText(String text) {
        return text.replace('\u00A0', ' ').replace("\r", "").replace("\n", "");
    }

    private record PhraseTimestamp(int start, int end, long startMillis, long durationMillis) {
    }

    record LyricLineMetaData(Duration startTime, Duration lineDuration, String lyric, LyricLine.Type type,
                             Map<Duration, Integer> phraseEndingOffsetMap) {
    }
}
