package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.beans.music.FormatType;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class AudioFormatDetector {
    private static final byte[] ID3_HEADER = {0x49, 0x44, 0x33};
    private static final byte[] FLAC_HEADER = {0x66, 0x4C, 0x61, 0x43};
    private static final byte[] RIFF_HEADER = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WAVE_HEADER = {0x57, 0x41, 0x56, 0x45};
    private static final byte[] OGG_HEADER = {0x4F, 0x67, 0x67, 0x53};
    private static final byte[] OPUS_HEADER = {0x4F, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64};
    private static final byte[] VORBIS_HEADER = {0x76, 0x6F, 0x72, 0x62, 0x69, 0x73};
    private static final byte[] FORM_HEADER = {0x46, 0x4F, 0x52, 0x4D};
    private static final byte[] AIFF_HEADER = {0x41, 0x49, 0x46, 0x46};
    private static final byte[] AIFC_HEADER = {0x41, 0x49, 0x46, 0x43};
    private static final byte[] AU_HEADER = {0x2E, 0x73, 0x6E, 0x64};

    private AudioFormatDetector() {
    }

    public static FormatType detectFormat(String identifier) throws IOException {
        String normalizedIdentifier = normalizeIdentifier(identifier);
        try (InputStream inputStream = openStream(normalizedIdentifier);
             BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream, 2048)) {
            return detectFormat(bufferedInputStream);
        } catch (IOException e) {
            return detectFormatFromName(normalizedIdentifier);
        }
    }

    public static String normalizeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Audio identifier cannot be blank");
        }
        String trimmed = identifier.trim();
        if (looksLikeWindowsAbsolutePath(trimmed)) {
            return Paths.get(trimmed).toAbsolutePath().normalize().toString();
        }
        try {
            Path directPath = Paths.get(trimmed);
            if (Files.exists(directPath)) {
                return directPath.toAbsolutePath().normalize().toString();
            }
        } catch (InvalidPathException ignored) {
        }
        try {
            URI uri = URI.create(trimmed);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return Paths.get(uri).toAbsolutePath().normalize().toString();
            }
            if (uri.getScheme() != null) {
                return uri.toString();
            }
        } catch (Exception ignored) {
        }
        return Paths.get(trimmed).toAbsolutePath().normalize().toString();
    }

    public static FormatType detectFormat(InputStream inputStream) throws IOException {
        if (!inputStream.markSupported()) {
            throw new IllegalArgumentException("InputStream must support mark/reset");
        }

        inputStream.mark(1024); // 增加标记大小以检测ID3标签
        byte[] header = new byte[1024];
        int bytesRead = inputStream.read(header);
        inputStream.reset();

        if (bytesRead < 4) {
            return FormatType.GENERIC;
        }

        // 检测FLAC格式
        if (header[0] == FLAC_HEADER[0] && header[1] == FLAC_HEADER[1] &&
                header[2] == FLAC_HEADER[2] && header[3] == FLAC_HEADER[3]) {
            return FormatType.FLAC;
        }

        // 检测WAV格式
        if (matches(header, RIFF_HEADER, 0) && bytesRead >= 12 && matches(header, WAVE_HEADER, 8)) {
            return FormatType.WAV;
        }

        // 检测OGG/Opus格式
        if (matches(header, OGG_HEADER, 0)) {
            if (contains(header, bytesRead, OPUS_HEADER)) {
                return FormatType.OPUS;
            }
            if (contains(header, bytesRead, VORBIS_HEADER)) {
                return FormatType.OGG;
            }
            return FormatType.GENERIC;
        }

        // 检测AIFF/AIFC格式
        if (matches(header, FORM_HEADER, 0) && bytesRead >= 12) {
            if (matches(header, AIFF_HEADER, 8) || matches(header, AIFC_HEADER, 8)) {
                return FormatType.AIFF;
            }
        }

        // 检测AU格式
        if (matches(header, AU_HEADER, 0)) {
            return FormatType.AU;
        }

        // 检测MP3格式（通过ID3标签）
        if (header[0] == ID3_HEADER[0] && header[1] == ID3_HEADER[1] &&
                header[2] == ID3_HEADER[2]) {
            return FormatType.MP3;
        }

        // 如果没有ID3标签，尝试检测MP3帧头（更复杂的检测）
        if (detectMP3FrameHeader(header, bytesRead)) {
            return FormatType.MP3;
        }

        // 常见AAC ADTS头
        if (detectAacAdtsHeader(header, bytesRead)) {
            return FormatType.AAC;
        }

        // 常见MP4/M4A容器
        if (detectMp4Family(header, bytesRead)) {
            return FormatType.M4A;
        }

        return FormatType.GENERIC;
    }

    private static boolean detectMP3FrameHeader(byte[] header, int length) {
        // MP3帧头检测：查找11个连续的1位（0xFF + 第二字节的高3位为111）
        for (int i = 0; i < length - 3; i++) {
            if ((header[i] & 0xFF) == 0xFF) {
                int secondByte = header[i + 1] & 0xFF;
                // 检查第二字节的高3位是否为111（MPEG版本和层信息）
                if ((secondByte & 0xE0) == 0xE0) {
                    // 进一步验证这是一个有效的MP3帧头
                    int thirdByte = header[i + 2] & 0xFF;

                    // 检查位率索引（不能是1111，表示无效）
                    if ((thirdByte & 0xF0) != 0xF0) {
                        // 检查采样率索引（不能是11，表示无效）
                        if ((thirdByte & 0x0C) != 0x0C) {
                            // 检查保护位、填充位等
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean detectAacAdtsHeader(byte[] header, int length) {
        if (length < 2) {
            return false;
        }
        return (header[0] & 0xFF) == 0xFF && (header[1] & 0xF6) == 0xF0;
    }

    private static boolean detectMp4Family(byte[] header, int length) {
        if (length < 12) {
            return false;
        }
        if (header[4] == 0x66 && header[5] == 0x74 && header[6] == 0x79 && header[7] == 0x70) {
            String brand = new String(header, 8, 4, StandardCharsets.US_ASCII);
            return brand.startsWith("M4A")
                    || brand.startsWith("isom")
                    || brand.startsWith("mp4")
                    || brand.startsWith("qt");
        }
        return false;
    }

    private static boolean matches(byte[] source, byte[] target, int offset) {
        if (offset + target.length > source.length) {
            return false;
        }
        for (int i = 0; i < target.length; i++) {
            if (source[offset + i] != target[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(byte[] source, int length, byte[] pattern) {
        if (pattern.length == 0 || length < pattern.length) {
            return false;
        }
        for (int i = 0; i <= length - pattern.length; i++) {
            if (matches(source, pattern, i)) {
                return true;
            }
        }
        return false;
    }

    private static InputStream openStream(String identifier) throws IOException {
        if (identifier.startsWith("http://") || identifier.startsWith("https://")) {
            URLConnection connection = URI.create(identifier).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            return connection.getInputStream();
        }
        return new FileInputStream(identifier);
    }

    private static FormatType detectFormatFromName(String identifier) {
        String lowerCase = identifier.toLowerCase(Locale.ROOT);
        if (lowerCase.endsWith(".flac")) {
            return FormatType.FLAC;
        }
        if (lowerCase.endsWith(".mp3")) {
            return FormatType.MP3;
        }
        if (lowerCase.endsWith(".wav") || lowerCase.endsWith(".wave")) {
            return FormatType.WAV;
        }
        if (lowerCase.endsWith(".ogg") || lowerCase.endsWith(".oga")) {
            return FormatType.OGG;
        }
        if (lowerCase.endsWith(".opus")) {
            return FormatType.OPUS;
        }
        if (lowerCase.endsWith(".aiff") || lowerCase.endsWith(".aif") || lowerCase.endsWith(".aifc")) {
            return FormatType.AIFF;
        }
        if (lowerCase.endsWith(".au") || lowerCase.endsWith(".snd")) {
            return FormatType.AU;
        }
        if (lowerCase.endsWith(".aac")) {
            return FormatType.AAC;
        }
        if (lowerCase.endsWith(".m4a") || lowerCase.endsWith(".mp4") || lowerCase.endsWith(".alac")) {
            return FormatType.M4A;
        }
        return FormatType.GENERIC;
    }

    private static boolean looksLikeWindowsAbsolutePath(String value) {
        return value.length() >= 3
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':'
                && (value.charAt(2) == '\\' || value.charAt(2) == '/');
    }
}

