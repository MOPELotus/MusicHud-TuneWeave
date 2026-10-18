package indi.mopelotus.musichud.audio.container.ts;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TsBoundaryTest {
  static InputStream fixture(String name) throws IOException {
    var input = TsBoundaryTest.class.getResourceAsStream("/container/ts/" + name + ".ts");
    if (input == null) throw new IOException("Missing TS fixture " + name);
    return input;
  }

  @Test
  void closeOwnsInputOnceAndRejectsFurtherReads() throws Exception {
    int[] closes = {0};
    var input =
        new ByteArrayInputStream(new byte[0]) {
          @Override
          public void close() {
            closes[0]++;
          }
        };
    var source = new TsPacketSource(input);
    source.close();
    source.close();
    assertEquals(1, closes[0]);
    assertThrows(IOException.class, source::next);
  }

  @Test
  void closeUnblocksReadWithoutHoldingReaderMonitor() throws Exception {
    var reading = new java.util.concurrent.CountDownLatch(1);
    int[] closes = {0};
    var input =
        new InputStream() {
          boolean closed;

          public synchronized int read() throws IOException {
            reading.countDown();
            while (!closed)
              try {
                wait();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
              }
            throw new IOException("Closed");
          }

          public synchronized void close() {
            closed = true;
            closes[0]++;
            notifyAll();
          }
        };
    try (var source = new TsPacketSource(input);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var result = executor.submit(() -> assertThrows(IOException.class, source::next));
      assertTrue(reading.await(2, java.util.concurrent.TimeUnit.SECONDS));
      source.close();
      result.get(2, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(1, closes[0]);
    }
  }

  @Test
  void malformedPacketTablesAndPesRejectBoundedly() throws Exception {
    for (String name :
        List.of(
            "conflicting-duplicate",
            "continuity-gap",
            "truncated-ts",
            "truncated-pes",
            "header-over-length",
            "pts-marker",
            "crc",
            "adaptation")) {
      try (var source = new TsPacketSource(fixture(name))) {
        assertThrows(
            IOException.class,
            () -> {
              while (source.next() != null) {}
            },
            name);
      }
    }
  }

  @Test
  void updatedPatPmtAndSplitPsiPreserveElementaryBytes() throws Exception {
    for (var expected :
        Map.of("pat-pmt-update", 194, "split-psi", 97, "pes-40000", 39997).entrySet()) {
      int bytes = 0;
      try (var source = new TsPacketSource(fixture(expected.getKey()))) {
        TsPacketSource.Packet packet;
        while ((packet = source.next()) != null) {
          bytes += packet.data().length;
          for (byte value : packet.data()) assertEquals((byte) 0x55, value);
        }
      }
      assertEquals(expected.getValue(), bytes, expected.getKey());
    }
  }
}
