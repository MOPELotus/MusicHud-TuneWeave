package indi.mopelotus.musichud.audio.container;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.mp4parser.*;
import org.mp4parser.boxes.iso14496.part12.*;
import org.mp4parser.boxes.iso14496.part14.ESDescriptorBox;
import org.mp4parser.boxes.sampleentry.SampleEntry;

/**
 * Synchronous MP4 packets; owned random-access channels can be local files or shared HTTP Range
 * adapters.
 */
public final class Mp4PacketSource implements PacketSource {
  private final CountedChannel channel;
  private final BoundedMedia media;
  private final IsoFile iso;
  private final Track info;
  private final Map<SampleEntry, Format> formats = new IdentityHashMap<>();
  private final Mp4SampleRanges ranges;
  private final List<SampleEntry> sampleEntries;
  private final long[] starts, durations;
  private final long[] compositionOffsets;
  private final long timescale;
  private int index;
  private volatile boolean closed;
  private boolean afterSeek;
  private Format previousFormat;

  private record Inputs(SeekableByteChannel metadata, SeekableByteChannel media) {}

  private static Inputs open(Path path) throws IOException {
    var c = Files.newByteChannel(path);
    try {
      return new Inputs(c, FileChannel.open(path, StandardOpenOption.READ));
    } catch (IOException | RuntimeException e) {
      try {
        c.close();
      } catch (IOException x) {
        e.addSuppressed(x);
      }
      throw e;
    }
  }

  public Mp4PacketSource(Path path) throws IOException {
    this(open(path));
  }

  private Mp4PacketSource(Inputs inputs) throws IOException {
    this(inputs.metadata, inputs.media);
  }

  /** Takes ownership of both inputs on entry, including initialization failure. */
  public Mp4PacketSource(SeekableByteChannel ownedMetadata, SeekableByteChannel ownedMedia)
      throws IOException {
    if (ownedMetadata == null || ownedMedia == null) {
      IOException e = new IOException("Both owned MP4 inputs are required");
      if (ownedMetadata != null)
        try {
          ownedMetadata.close();
        } catch (IOException x) {
          e.addSuppressed(x);
        }
      if (ownedMedia != null)
        try {
          ownedMedia.close();
        } catch (IOException x) {
          e.addSuppressed(x);
        }
      throw e;
    }
    if (ownedMetadata == ownedMedia) {
      IOException e = new IOException("Independent metadata/media channel views are required");
      try {
        ownedMetadata.close();
      } catch (IOException x) {
        e.addSuppressed(x);
      }
      throw e;
    }
    channel = new CountedChannel(ownedMetadata);
    BoundedMedia allocatedMedia = null;
    IsoFile opened = null;
    try {
      allocatedMedia = new BoundedMedia(new ChannelMedia(ownedMedia), channel.size());
      media = allocatedMedia;
      long[] rootOffsets = Mp4Preflight.validate(channel);
      channel.position(0);
      var parser = new Mp4BoxParser();
      opened = new IsoFile(channel, parser);
      iso = opened;
      if (iso.getMovieBox() == null) throw new IOException("Missing MP4 movie");
      TrackBox selected =
          iso.getMovieBox().getBoxes(TrackBox.class).stream()
              .filter(t -> "soun".equals(t.getMediaBox().getHandlerBox().getHandlerType()))
              .findFirst()
              .orElseThrow(() -> new IOException("No audio track"));
      long trackId = selected.getTrackHeaderBox().getTrackId();
      timescale = selected.getMediaBox().getMediaHeaderBox().getTimescale();
      if (timescale <= 0) throw new IOException("Invalid media timescale");
      sampleEntries =
          List.copyOf(
              selected.getSampleTableBox().getSampleDescriptionBox().getBoxes(SampleEntry.class));
      if (sampleEntries.size()
          != selected.getSampleTableBox().getSampleDescriptionBox().getBoxes().size())
        throw new IOException("Unsupported audio sample-description entry");
      for (var entry : sampleEntries) formats.put(entry, format(entry));
      if (formats.isEmpty()) throw new IOException("Missing sample entry");
      durations = Mp4SampleTiming.durations(iso, selected);
      starts = new long[durations.length + 1];
      for (int i = 0; i < durations.length; i++) {
        if (durations[i] < 0) throw new IOException("Negative sample duration");
        starts[i + 1] = Math.addExact(starts[i], durations[i]);
      }
      ranges = new Mp4SampleRanges(iso, selected, durations.length, channel.size(), rootOffsets);
      for (int description : ranges.descriptions)
        if (description < 1 || description > sampleEntries.size())
          throw new IOException("Invalid sample-description index");
      preserveFragmentTimes(selected, trackId);
      compositionOffsets = compositionOffsets(selected, trackId);
      var edits = new ArrayList<Edit>();
      var elst = org.mp4parser.tools.Path.<EditListBox>getPath(selected, "edts/elst");
      long movieScale = iso.getMovieBox().getMovieHeaderBox().getTimescale();
      if (movieScale <= 0) throw new IOException("Invalid movie timescale");
      if (elst != null)
        for (var e : elst.getEntries()) {
          if (e.getSegmentDuration() < 0 || e.getMediaTime() < -1)
            throw new IOException("Invalid edit range");
          long fixedRate = Math.round(e.getMediaRate() * 65536.0);
          edits.add(
              new Edit(
                  e.getMediaTime() == -1 ? null : new Time(e.getMediaTime(), timescale),
                  new Time(e.getSegmentDuration(), movieScale),
                  new Rate(fixedRate, 65536)));
        }
      info =
          new Track(
              formats.get(
                  sampleEntries.get(
                      ranges.descriptions.length == 0 ? 0 : ranges.descriptions[0] - 1)),
              new Time(starts[durations.length], timescale),
              edits,
              Time.ZERO,
              Time.ZERO);
    } catch (IOException | RuntimeException | AssertionError e) {
      if (opened != null)
        try {
          opened.close();
        } catch (Exception x) {
          e.addSuppressed(x);
        }
      try {
        channel.close();
      } catch (Exception x) {
        e.addSuppressed(x);
      }
      try {
        if (allocatedMedia != null) allocatedMedia.close();
        else ownedMedia.close();
      } catch (Exception x) {
        e.addSuppressed(x);
      }
      if (e instanceof IOException x) throw x;
      throw new IOException("Invalid MP4", e);
    }
  }

  private Format format(SampleEntry entry) throws IOException {
    byte[] config = new byte[0];
    String codec = entry.getType().equals(".mp3") ? "mp3" : entry.getType();
    if (entry.getType().equals("mp4a")) {
      var esds = org.mp4parser.tools.Path.<ESDescriptorBox>getPath((Container) entry, "esds");
      if (esds == null) throw new IOException("AAC without esds");
      var description = RawEsds.description(esds.getData());
      config = description.decoderSpecificInfo();
      if (description.objectType() == 0x69 || description.objectType() == 0x6b) codec = "mp3";
      else if (description.objectType() == 0x40
          || description.objectType() >= 0x66 && description.objectType() <= 0x68) {
        if (config.length == 0) throw new IOException("AAC requires DecoderSpecificInfo");
      } else codec = "mp4-object-" + Integer.toHexString(description.objectType());
    } else if (entry.getType().equals("alac")) {
      var box =
          entry.getBoxes().stream()
              .filter(b -> b.getType().equals("alac"))
              .findFirst()
              .orElseThrow(() -> new IOException("ALAC without config"));
      if (!(box instanceof org.mp4parser.boxes.UnknownBox raw))
        throw new IOException("Unexpected ALAC configuration box");
      // UnknownBox parses by advancing its shared buffer to the end. Assertions may
      // rewind that buffer during verification, so its cursor is not a payload boundary.
      var payload = raw.getData().duplicate();
      payload.rewind();
      var bytes = ByteBuffer.allocate(Math.addExact(8, payload.remaining()));
      bytes.putInt(bytes.capacity()).putInt(0x616c6163).put(payload);
      config = bytes.array();
    } else if (entry.getType().equals("enca"))
      throw new IOException("Encrypted MP4 requires a separate DRM implementation");
    return new Format(codec, config);
  }

  private long[] compositionOffsets(TrackBox selected, long trackId) throws IOException {
    var classic = selected.getSampleTableBox().getCompositionTimeToSample();
    boolean present = classic != null;
    for (var moof : iso.getBoxes(MovieFragmentBox.class))
      for (var traf : moof.getBoxes(TrackFragmentBox.class))
        if (traf.getTrackFragmentHeaderBox().getTrackId() == trackId)
          for (var run : traf.getBoxes(TrackRunBox.class))
            present |= run.isSampleCompositionTimeOffsetPresent();
    if (!present) return null;
    long[] result = new long[durations.length];
    var stsz = selected.getSampleTableBox().getSampleSizeBox();
    int initial = stsz == null ? 0 : Math.toIntExact(stsz.getSampleCount()), at = 0;
    if (classic != null) {
      for (var entry : classic.getEntries()) {
        if (entry.getCount() < 0 || entry.getCount() > initial - at)
          throw new IOException("Composition count exceeds samples");
        long value =
            classic.getVersion() == 0
                ? Integer.toUnsignedLong(entry.getOffset())
                : entry.getOffset();
        Arrays.fill(result, at, at + entry.getCount(), value);
        at += entry.getCount();
      }
      if (at != initial) throw new IOException("Composition count mismatch");
    }
    at = initial;
    for (var moof : iso.getBoxes(MovieFragmentBox.class))
      for (var traf : moof.getBoxes(TrackFragmentBox.class))
        if (traf.getTrackFragmentHeaderBox().getTrackId() == trackId)
          for (var run : traf.getBoxes(TrackRunBox.class))
            for (var entry : run.getEntries()) {
              if (at >= result.length)
                throw new IOException("Fragment composition count exceeds samples");
              result[at++] =
                  run.isSampleCompositionTimeOffsetPresent()
                      ? entry.getSampleCompositionTimeOffset()
                      : 0;
            }
    if (at != result.length) throw new IOException("Fragment composition count mismatch");
    return result;
  }

  private void preserveFragmentTimes(TrackBox selected, long trackId) throws IOException {
    var fragments = iso.getBoxes(MovieFragmentBox.class);
    if (fragments.isEmpty()) return;
    var stsz = selected.getSampleTableBox().getSampleSizeBox();
    int at = stsz == null ? 0 : Math.toIntExact(stsz.getSampleCount());
    if (at > durations.length) throw new IOException("Initial sample count exceeds track");
    long end = starts[at];
    for (var moof : fragments)
      for (var traf : moof.getBoxes(TrackFragmentBox.class))
        if (traf.getTrackFragmentHeaderBox().getTrackId() == trackId) {
          var tfdt = traf.getBoxes(TrackFragmentBaseMediaDecodeTimeBox.class);
          long time = tfdt.isEmpty() ? end : tfdt.getFirst().getBaseMediaDecodeTime();
          if (time < end) throw new IOException("Fragment decode time goes backwards");
          for (var run : traf.getBoxes(TrackRunBox.class))
            for (long i = 0; i < run.getSampleCount(); i++) {
              if (at >= durations.length) throw new IOException("Fragment count exceeds track");
              starts[at] = time;
              time = Math.addExact(time, durations[at++]);
            }
          end = time;
        }
    if (at != durations.length) throw new IOException("Fragment sample count mismatch");
    starts[at] = end;
  }

  public Track track() {
    return info;
  }

  public long metadataBytesRead() {
    return channel.readBytes;
  }

  public Packet next() throws IOException {
    if (closed) throw new IOException("Source closed");
    if (index == durations.length) return null;
    int i = index++;
    try {
      int description = ranges.descriptions[i] - 1;
      if (description >= sampleEntries.size())
        throw new IOException("Sample description outside table");
      var format = formats.get(sampleEntries.get(description));
      if (format == null) throw new IOException("Unknown sample description");
      long pts = Math.addExact(starts[i], compositionOffsets == null ? 0 : compositionOffsets[i]);
      boolean discontinuity =
          afterSeek || (previousFormat != null && !previousFormat.equals(format));
      var packet =
          new Packet(
              format,
              new Time(starts[i], timescale),
              new Time(pts, timescale),
              new Time(durations[i], timescale),
              Time.ZERO,
              Time.ZERO,
              discontinuity,
              media.get(ranges.offsets[i], ranges.sizes[i]));
      if (closed) throw new IOException("Source closed during read");
      afterSeek = false;
      previousFormat = format;
      return packet;
    } catch (RuntimeException e) {
      throw new IOException("Invalid MP4 sample", e);
    }
  }

  public Time seekMedia(Time target) throws IOException {
    if (closed) throw new IOException("Source closed");
    if (target == null || target.ticks() < 0) throw new IOException("Invalid media seek");
    long ticks;
    try {
      ticks =
          BigInteger.valueOf(target.ticks())
              .multiply(BigInteger.valueOf(timescale))
              .divide(BigInteger.valueOf(target.timescale()))
              .longValueExact();
    } catch (ArithmeticException e) {
      throw new IOException("Seek exceeds timestamp range", e);
    }
    int p = Arrays.binarySearch(starts, ticks);
    index = Math.min(Math.max(p >= 0 ? p : -p - 2, 0), durations.length);
    afterSeek = true;
    previousFormat = null;
    return index == durations.length ? null : new Time(starts[index], timescale);
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    IOException failure = null;
    try {
      iso.close();
    } catch (IOException e) {
      failure = e;
    }
    try {
      channel.close();
    } catch (IOException e) {
      if (failure == null) failure = e;
      else failure.addSuppressed(e);
    }
    try {
      media.close();
    } catch (IOException e) {
      if (failure == null) failure = e;
      else failure.addSuppressed(e);
    }
    if (failure != null) throw failure;
  }

  static final class RawDescriptorBox extends ESDescriptorBox {
    @Override
    public void _parseDetails(ByteBuffer content) {
      parseVersionAndFlags(content);
      data = content.slice();
      content.position(content.limit());
    }
  }

  /** JDK exact positional reads: upstream FileRandomAccessSourceImpl uses a single short read. */
  private interface RangeReader extends Closeable {
    ByteBuffer get(long offset, long length) throws IOException;
  }

  static final class ChannelMedia implements RangeReader {
    private final SeekableByteChannel file;

    ChannelMedia(SeekableByteChannel file) {
      this.file = file;
    }

    public ByteBuffer get(long offset, long count) throws IOException {
      if (offset < 0 || count < 0 || count > 16 * 1024 * 1024)
        throw new IOException("Invalid media extent");
      ByteBuffer bytes = ByteBuffer.allocate((int) count);
      if (!(file instanceof FileChannel)) file.position(offset);
      int stalled = 0;
      while (bytes.hasRemaining()) {
        int n =
            file instanceof FileChannel channel
                ? channel.read(bytes, Math.addExact(offset, bytes.position()))
                : file.read(bytes);
        if (n < 0) throw new EOFException("Truncated MP4 media packet");
        if (n == 0) {
          if (++stalled > 8) throw new IOException("MP4 media input made no progress");
        } else stalled = 0;
      }
      return bytes.flip();
    }

    public void close() throws IOException {
      file.close();
    }
  }

  static final class BoundedMedia implements RangeReader {
    final RangeReader delegate;
    final long size;
    boolean closed;

    BoundedMedia(RangeReader delegate, long size) {
      this.delegate = delegate;
      this.size = size;
    }

    public ByteBuffer get(long offset, long count) throws IOException {
      if (offset < 0 || count < 0 || count > 16 * 1024 * 1024 || offset > size - count)
        throw new IOException("MP4 media extent exceeds bound");
      var bytes = delegate.get(offset, count);
      if (bytes.remaining() != count) throw new EOFException("Short MP4 media read");
      return bytes;
    }

    public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      delegate.close();
    }
  }

  static final class CountedChannel implements SeekableByteChannel {
    final SeekableByteChannel delegate;
    long readBytes;
    boolean closed;

    CountedChannel(SeekableByteChannel d) {
      delegate = d;
    }

    public int read(ByteBuffer b) throws IOException {
      if (!b.hasRemaining()) return 0;
      int count = 0, stalled = 0;
      while (b.hasRemaining()) {
        int n = delegate.read(b);
        if (n < 0) {
          if (count == 0) return -1;
          throw new EOFException("Truncated MP4 metadata read");
        }
        if (n == 0) {
          if (++stalled > 8) throw new IOException("Metadata input made no progress");
        } else {
          count += n;
          readBytes += n;
          stalled = 0;
        }
      }
      return count;
    }

    public int write(ByteBuffer b) {
      throw new NonWritableChannelException();
    }

    public long position() throws IOException {
      return delegate.position();
    }

    public SeekableByteChannel position(long n) throws IOException {
      delegate.position(n);
      return this;
    }

    public long size() throws IOException {
      return delegate.size();
    }

    public SeekableByteChannel truncate(long n) {
      throw new NonWritableChannelException();
    }

    public boolean isOpen() {
      return !closed && delegate.isOpen();
    }

    public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      delegate.close();
    }
  }

  static final class SkippedPayload implements ParsableBox {
    long size;
    final String type;

    SkippedPayload(String type) {
      this.type = type;
    }

    public String getType() {
      return type;
    }

    public long getSize() {
      return size;
    }

    public void getBox(WritableByteChannel c) {
      throw new UnsupportedOperationException("read-only");
    }

    public void parse(ReadableByteChannel input, ByteBuffer header, long payload, BoxParser parser)
        throws IOException {
      if (!(input instanceof SeekableByteChannel c))
        throw new IOException("Seekable MP4 metadata input required");
      long next;
      try {
        next = Math.addExact(c.position(), payload);
        size = Math.addExact(payload, header.limit());
      } catch (ArithmeticException e) {
        throw new IOException("Media size overflow", e);
      }
      if (payload < 0 || next > c.size()) throw new EOFException("Invalid media extent");
      c.position(next);
    }
  }
}
