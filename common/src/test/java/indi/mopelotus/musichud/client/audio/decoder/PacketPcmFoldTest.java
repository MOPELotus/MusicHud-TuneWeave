package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.audio.codec.PcmFormat;
import java.io.*;
import java.nio.*;

class PacketPcmFoldTest {
  static int assertions;

  static void yes(boolean value, String what) {
    assertions++;
    if (!value) throw new AssertionError(what);
  }

  @org.junit.jupiter.api.Test
  void presentationBoundaryChecks() throws Exception {
    var multi = new PcmFormat(44100, 6, PcmFormat.Encoding.F32LE, PcmFormat.Layout.VORBIS);
    for (int channel = 0; channel < 6; channel++) {
      var fold = new PacketPcmFold(multi);
      var bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
      for (int c = 0; c < 6; c++) bytes.putFloat(channel == c ? 0.5f : 0);
      bytes.flip();
      var stereo = fold.convert(bytes, 1);
      yes(fold.hasDownmixedChannels(), "fold reported");
      if (channel == 0 || channel == 3)
        yes(stereo[0] > 0 && stereo[1] == 0, "left speaker " + channel);
      if (channel == 2 || channel == 4)
        yes(stereo[1] > 0 && stereo[0] == 0, "right speaker " + channel);
      if (channel == 1 || channel == 5)
        yes(stereo[0] > 0 && stereo[0] == stereo[1], "center/LFE preserved " + channel);
    }
    var mono =
        new PacketPcmFold(new PcmFormat(48000, 1, PcmFormat.Encoding.S24LE, PcmFormat.Layout.MONO));
    var small = mono.convert(ByteBuffer.wrap(new byte[] {1, 0, 0}), 1);
    yes(small[0] == 1f / 8388608 && small[1] == small[0], "24bit precision+mono duplicate");
    yes(!mono.hasDownmixedChannels(), "mono duplicate not fold");
    var signed =
        new PacketPcmFold(
            new PcmFormat(48000, 2, PcmFormat.Encoding.S16LE, PcmFormat.Layout.STEREO));
    var shorts =
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort((short) -1)
            .putShort(Short.MIN_VALUE);
    shorts.flip();
    var f = signed.convert(shorts, 1);
    yes(f[0] == -1f / 32768 && f[1] == -1, "integer signed normalize");
    try {
      new PacketPcmFold(
          new PcmFormat(48000, 6, PcmFormat.Encoding.F32LE, PcmFormat.Layout.UNSPECIFIED));
      throw new AssertionError("guessed layout");
    } catch (IOException expected) {
      assertions++;
    }
    var fp =
        new PacketPcmFold(new PcmFormat(48000, 1, PcmFormat.Encoding.F32LE, PcmFormat.Layout.MONO));
    var nan = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN);
    nan.flip();
    try {
      fp.convert(nan, 1);
      throw new AssertionError("NaN accepted");
    } catch (IOException expected) {
      assertions++;
    }

    var swapped =
        new PcmFormat(
            48000,
            2,
            PcmFormat.Encoding.F32LE,
            PcmFormat.Layout.UNSPECIFIED,
            new PcmFormat.SpeakerLayout(3, java.util.List.of(2, 1)));
    var explicit = new PacketPcmFold(swapped);
    var swapbytes =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(.25f).putFloat(.5f);
    swapbytes.flip();
    var swappedStereo = explicit.convert(swapbytes, 1);
    yes(
        swappedStereo[0] == .5f && swappedStereo[1] == .25f,
        "explicit speaker order wins before fallback");
    yes(!explicit.hasDownmixedChannels(), "speaker reorder alone not downmix");
    var monoRight =
        new PacketPcmFold(
            new PcmFormat(
                48000,
                1,
                PcmFormat.Encoding.F32LE,
                PcmFormat.Layout.UNSPECIFIED,
                new PcmFormat.SpeakerLayout(2, java.util.List.of(2))));
    var monobytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(.25f);
    monobytes.flip();
    var onlyRight = monoRight.convert(monobytes, 1);
    yes(
        onlyRight[0] == 0 && onlyRight[1] == .25f && monoRight.hasDownmixedChannels(),
        "noncenter mono speaker safely marked mixed");

    System.out.println("fold assertions=" + assertions + " PASS");
  }
}
