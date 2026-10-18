package indi.mopelotus.musichud.client.audio.decoder.transport;

import org.junit.jupiter.api.Test;

class BodyTerminationTest {
  @Test
  void queuePublicationCannotSkipBytesErrorsOrCloseSignals() throws Exception {
    BodyTerminationChecks.main(new String[0]);
  }
}
