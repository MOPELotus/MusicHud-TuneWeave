package indi.mopelotus.musichud.audio.container;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.container.matroska.format.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MatroskaLacingTest {
  private static class Element extends MutableMatroskaElement {
    Element(long size) {
      super(0);
      setPosition(0);
      setHeaderSize(0);
      setDataSize(size);
    }
  }

  @Test
  void xiph511PlusOneDoesNotConsumeTwoBytesAsPayload() throws Exception {
    byte[] bytes = new byte[8 + 512];
    byte[] header = {(byte) 0x81, 0, 0, (byte) 0x82, 1, (byte) 255, (byte) 255, 1};
    System.arraycopy(header, 0, bytes, 0, header.length);
    Arrays.fill(bytes, 8, bytes.length - 1, (byte) 0x5a);
    bytes[bytes.length - 1] = 0x33;
    try (var input = new ContainerLifecycleTest.Memory(bytes)) {
      var reader = new MatroskaFileReader(input);
      var block = new MutableMatroskaBlock();
      assertTrue(block.parseHeader(reader, new Element(bytes.length), 1));
      assertEquals(2, block.getFrameCount());
      var first = block.getNextFrameBuffer(reader, 0);
      assertEquals(511, first.remaining());
      while (first.hasRemaining()) assertEquals((byte) 0x5a, first.get());
      var second = block.getNextFrameBuffer(reader, 1);
      assertEquals(1, second.remaining());
      assertEquals(0x33, second.get() & 255);
    }
  }

  @Test
  void malformedLaceSizesRejectWithoutAllocation() throws Exception {
    for (byte[] bytes :
        List.of(
            new byte[] {
              (byte) 0x81, 0, 0, (byte) 0x84, 1, 1, 2, 3
            }, // 3 bytes cannot split into 2 fixed laces
            new byte[] {
              (byte) 0x81, 0, 0, (byte) 0x82, 1, (byte) 255
            }, // Xiph size never terminates
            new byte[] {
              (byte) 0x81, 0, 0, (byte) 0x86, 1, (byte) 0x84, 1, 2, 3
            })) { // first EBML frame exceeds payload
      try (var input = new ContainerLifecycleTest.Memory(bytes)) {
        var reader = new MatroskaFileReader(input);
        var block = new MutableMatroskaBlock();
        assertThrows(
            IOException.class, () -> block.parseHeader(reader, new Element(bytes.length), 1));
      }
    }
  }

  @Test
  void truncatedAndOutOfParentElementsAreIoFailures() throws Exception {
    for (byte[] bytes :
        List.of(
            new byte[] {(byte) 0xa3, (byte) 0x84, 1},
            new byte[] {(byte) 0xa3, 0},
            new byte[] {(byte) 0xa3, (byte) 0xff})) {
      try (var input = new ContainerLifecycleTest.Memory(bytes)) {
        assertThrows(IOException.class, () -> new MatroskaFileReader(input).readNextElement(null));
      }
    }
  }
}
