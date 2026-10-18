package indi.mopelotus.musichud.client.audio.decoder.transport;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IcyTransportTest {
  @TempDir Path temporary;

  @Test
  void preservesIcyStatusAndDecodedMetadataIntervalsWithStrictBoundaries() throws Exception {
    IcyTransportChecks.main(new String[] {temporary.toString()});
  }
}
