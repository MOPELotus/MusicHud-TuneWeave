package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport;
import indi.mopelotus.musichud.interfaces.ClientRegister;
import indi.mopelotus.musichud.interfaces.ICommonEventService;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class PcmAudioInput {
  private static MediaTransport transport;
  private static boolean stopped;

  private PcmAudioInput() {}

  /** Shared media transport with connection-level DNS and per-hop redirect validation. */
  public static InputStream openPublicMedia(String identifier, Map<String, String> headers)
      throws IOException {
    return open(identifier, headers, true);
  }

  static InputStream open(String identifier, Map<String, String> headers, boolean publicOnly)
      throws IOException {
    if (!identifier.regionMatches(true, 0, "http://", 0, 7)
        && !identifier.regionMatches(true, 0, "https://", 0, 8)) {
      if (publicOnly) throw new IOException("Public audio must use HTTP");
      return Files.newInputStream(Path.of(identifier));
    }
    final URI uri;
    try {
      uri = URI.create(identifier);
    } catch (IllegalArgumentException error) {
      throw new IOException("Invalid media URL");
    }
    return transport()
        .open(uri, headers, publicOnly, null, new MediaTransport.Cancellation())
        .body();
  }

  static MediaTransport sharedTransport() throws IOException {
    return transport();
  }

  private static synchronized MediaTransport transport() throws IOException {
    if (stopped) throw new IOException("Media transport has stopped");
    if (transport == null) transport = new MediaTransport();
    return transport;
  }

  /** Stops shared download resources; requests arriving after shutdown cannot recreate them. */
  public static void closeTransport() {
    MediaTransport owned;
    synchronized (PcmAudioInput.class) {
      stopped = true;
      owned = transport;
      transport = null;
    }
    if (owned != null) owned.close();
  }

  @RegisterMark
  public static final class RegisterImpl implements ClientRegister {
    @Override
    public void register() {
      ICommonEventService.getInstance()
          .registerCommonLifecycleStopping(PcmAudioInput::closeTransport);
    }
  }
}
