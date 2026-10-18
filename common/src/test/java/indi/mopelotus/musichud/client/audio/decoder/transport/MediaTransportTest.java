package indi.mopelotus.musichud.client.audio.decoder.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaTransportTest {
  @TempDir Path temporary;

  @Test
  void validatesRealSocketTransportBoundaries() throws Exception {
    MediaTransportSocketChecks.main(
        new String[] {temporary.resolve("socket-results.json").toString()});
  }

  @Test
  void preservesTlsHostnameAndTrustVerificationOnPinnedSockets() throws Exception {
    Path store = temporary.resolve("synthetic-test-only.p12");
    String executable =
        System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair",
                "-alias",
                "local-test",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=media.test",
                "-ext",
                "SAN=dns:media.test",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "test-only-password",
                "-keypass",
                "test-only-password",
                "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(temporary.resolve("keytool.log").toFile())
            .start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Test certificate generation timed out");
      assertEquals(
          0,
          process.exitValue(),
          () -> {
            try {
              return Files.readString(temporary.resolve("keytool.log"));
            } catch (Exception ignored) {
              return "keytool failed";
            }
          });
    } finally {
      process.destroyForcibly();
    }
    MediaTransportTlsChecks.main(
        new String[] {store.toString(), temporary.resolve("tls-results.json").toString()});
  }
}
