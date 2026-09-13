package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.FormatType;
import indi.mopelotus.musichud.server.playback.SharedResourceValidator;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;

public final class AudioDecoderFactory {
    private static final Logger LOGGER = MusicHud.getLogger(AudioDecoderFactory.class);
    private static final int DEFAULT_PROBE_BYTES = 65536;

    private AudioDecoderFactory() {
    }

    public static AudioDecoder open(String identifier, FormatType declaredFormat) {
        return open(identifier, declaredFormat, Map.of());
    }

    public static AudioDecoder open(String identifier, FormatType declaredFormat, Map<String, String> headers) {
        return open(identifier, declaredFormat, headers, false);
    }

    public static AudioDecoder open(String identifier, FormatType declaredFormat, Map<String, String> headers, boolean floatSupported) {
        return open(identifier, declaredFormat, headers, floatSupported, false);
    }
    public static AudioDecoder open(String identifier, FormatType declaredFormat, Map<String, String> headers, boolean floatSupported, boolean multichannelSupported) {
        String normalizedIdentifier = AudioFormatDetector.normalizeIdentifier(identifier);
        try {
            AudioDecoder nativeDecoder = openNative(normalizedIdentifier, declaredFormat, headers, false, floatSupported, multichannelSupported);
            if (nativeDecoder != null) return nativeDecoder;
            return LavaplayerStreamDecoder.open(normalizedIdentifier, headers);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open audio decoder for: " + normalizedIdentifier, e);
        }
    }

    public static AudioDecoder openPublicResource(String identifier, FormatType declaredFormat,
                                                  Map<String, String> headers) {
        return openPublicResource(identifier, declaredFormat, headers, false);
    }

    public static AudioDecoder openPublicResource(String identifier, FormatType declaredFormat,
                                                  Map<String, String> headers, boolean floatSupported) {
        return openPublicResource(identifier, declaredFormat, headers, floatSupported, false);
    }
    public static AudioDecoder openPublicResource(String identifier, FormatType declaredFormat,
            Map<String, String> headers, boolean floatSupported, boolean multichannelSupported) {
        String normalizedIdentifier = AudioFormatDetector.normalizeIdentifier(identifier);
        try {
            SharedResourceValidator.requireSafeHttpUrl(normalizedIdentifier);
            AudioDecoder nativeDecoder = openNative(normalizedIdentifier, declaredFormat, headers, true, floatSupported, multichannelSupported);
            if (nativeDecoder != null) return nativeDecoder;
            return LavaplayerStreamDecoder.openPublicResource(normalizedIdentifier, headers);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid public audio resource", error);
        } catch (IOException error) {
            throw new RuntimeException(
                    "Failed to open public audio decoder for: " + normalizedIdentifier, error);
        }
    }

    private static AudioDecoder openNative(String identifier, FormatType declared, Map<String, String> headers,
                                           boolean publicOnly, boolean floatSupported, boolean multichannelSupported) throws IOException {
        if (declared != FormatType.FLAC && declared != FormatType.WAV && declared != FormatType.AUTO) return null;
        var input = new java.io.BufferedInputStream(PcmAudioInput.open(identifier, headers, publicOnly));
        boolean owned = false;
        try {
            input.mark(12);
            byte[] signature = input.readNBytes(12);
            input.reset();
            String prefix = new String(signature, java.nio.charset.StandardCharsets.ISO_8859_1);
            FormatType detected = prefix.startsWith("fLaC") ? FormatType.FLAC
                    : prefix.startsWith("RIFF") && prefix.length() == 12 && prefix.substring(8).equals("WAVE")
                    ? FormatType.WAV : FormatType.GENERIC;
            AudioDecoder decoder = switch (detected) {
                case WAV -> new WavPcmDecoder(input, floatSupported, multichannelSupported);
                case FLAC -> new FlacPcmDecoder(input, floatSupported, multichannelSupported);
                default -> null;
            };
            owned = decoder != null;
            return decoder;
        } catch (java.io.UnsupportedEncodingException unsupported) {
            return null;
        } finally { if (!owned) input.close(); }
    }

    public static AudioDecodeProbe probe(String identifier, FormatType declaredFormat) throws IOException {
        String normalizedIdentifier = AudioFormatDetector.normalizeIdentifier(identifier);
        FormatType detectedFormat = AudioFormatDetector.detectFormat(normalizedIdentifier);
        try (AudioDecoder decoder = open(normalizedIdentifier, declaredFormat, Map.of(), false)) {
            byte[] sample = decoder.readChunk(DEFAULT_PROBE_BYTES);
            int probeBytesRead = sample == null ? 0 : sample.length;
            return new AudioDecodeProbe(
                    normalizedIdentifier,
                    declaredFormat,
                    detectedFormat,
                    decoder instanceof LavaplayerStreamDecoder lava ? lava.getBackendName() : decoder.getClass().getSimpleName(),
                    decoder.getFrameSize() / (decoder.getSampleEncoding() == AudioDecoder.SampleEncoding.PCM_F32_LE ? 4 : 2),
                    decoder.getSampleRate(),
                    decoder.getFormat(),
                    probeBytesRead
            );
        } catch (RuntimeException e) {
            LOGGER.debug("Failed to probe decoder for {}", normalizedIdentifier, e);
            throw new IOException("Failed to probe audio decoder", e);
        }
    }
}

