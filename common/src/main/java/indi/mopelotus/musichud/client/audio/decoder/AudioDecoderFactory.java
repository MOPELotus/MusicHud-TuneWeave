package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.beans.music.FormatType;
import indi.mopelotus.musichud.server.playback.SharedResourceValidator;
import java.io.IOException;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class AudioDecoderFactory {
  private static final Logger LOGGER = LogManager.getLogger(AudioDecoderFactory.class);
  private static final int DEFAULT_PROBE_BYTES = 65536;

  private AudioDecoderFactory() {}

  public static AudioDecoder open(String identifier, FormatType declaredFormat) {
    return open(identifier, declaredFormat, Map.of());
  }

  public static AudioDecoder open(
      String identifier, FormatType declaredFormat, Map<String, String> headers) {
    return open(identifier, declaredFormat, headers, false);
  }

  public static AudioDecoder open(
      String identifier,
      FormatType declaredFormat,
      Map<String, String> headers,
      boolean floatSupported) {
    return open(identifier, declaredFormat, headers, floatSupported, false);
  }

  public static AudioDecoder open(
      String identifier,
      FormatType declaredFormat,
      Map<String, String> headers,
      boolean floatSupported,
      boolean multichannelSupported) {
    String normalizedIdentifier = AudioFormatDetector.normalizeIdentifier(identifier);
    try {
      return JavaDecoderFactory.open(
          normalizedIdentifier, headers, false, floatSupported, multichannelSupported);
    } catch (IOException e) {
      throw new RuntimeException("Failed to open audio decoder", e);
    }
  }

  public static AudioDecoder openPublicResource(
      String identifier, FormatType declaredFormat, Map<String, String> headers) {
    return openPublicResource(identifier, declaredFormat, headers, false);
  }

  public static AudioDecoder openPublicResource(
      String identifier,
      FormatType declaredFormat,
      Map<String, String> headers,
      boolean floatSupported) {
    return openPublicResource(identifier, declaredFormat, headers, floatSupported, false);
  }

  public static AudioDecoder openPublicResource(
      String identifier,
      FormatType declaredFormat,
      Map<String, String> headers,
      boolean floatSupported,
      boolean multichannelSupported) {
    String normalizedIdentifier = AudioFormatDetector.normalizeIdentifier(identifier);
    try {
      SharedResourceValidator.requireSafeHttpUrl(normalizedIdentifier);
      return JavaDecoderFactory.open(
          normalizedIdentifier, headers, true, floatSupported, multichannelSupported);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Invalid public audio resource", error);
    } catch (IOException error) {
      throw new RuntimeException("Failed to open public audio decoder", error);
    }
  }

  public static AudioDecodeProbe probe(String identifier, FormatType declaredFormat)
      throws IOException {
    String normalizedIdentifier = AudioFormatDetector.normalizeIdentifier(identifier);
    try (AudioDecoder decoder = open(normalizedIdentifier, declaredFormat, Map.of(), false)) {
      byte[] sample = decoder.readChunk(DEFAULT_PROBE_BYTES);
      int probeBytesRead = sample == null ? 0 : sample.length;
      return new AudioDecodeProbe(
          normalizedIdentifier,
          declaredFormat,
          decoder instanceof ManagedAudioDecoder managed
              ? managed.detectedFormat()
              : FormatType.GENERIC,
          decoder instanceof ManagedAudioDecoder managed
              ? managed.backendName()
              : decoder.getClass().getSimpleName(),
          decoder.getFrameSize()
              / (decoder.getSampleEncoding() == AudioDecoder.SampleEncoding.PCM_F32_LE ? 4 : 2),
          decoder.getSampleRate(),
          decoder.getFormat(),
          probeBytesRead);
    } catch (RuntimeException e) {
      LOGGER.debug("Failed to probe audio decoder");
      throw new IOException("Failed to probe audio decoder", e);
    }
  }
}
