package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.audio.codec.*;
import indi.mopelotus.musichud.audio.container.*;
import indi.mopelotus.musichud.audio.container.aac.AdtsPacketSource;
import indi.mopelotus.musichud.audio.container.input.PlaylistResolver;
import indi.mopelotus.musichud.beans.music.FormatType;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.*;
import indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Internal Java backend assembly. Device conversion and presentation edits retain one owner. */
final class JavaDecoderFactory {
  private JavaDecoderFactory() {}

  static AudioDecoder open(
      String identifier,
      Map<String, String> headers,
      boolean publicOnly,
      boolean floatSupported,
      boolean multichannelSupported)
      throws IOException {
    try {
      return open(identifier, headers, publicOnly, floatSupported, multichannelSupported, false);
    } catch (IOException failure) {
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof MediaTransport.RangeNotHonoredException ignoredRange)
          return open(identifier, headers, publicOnly, floatSupported, multichannelSupported, true,
              ignoredRange.expected());
        if (cause
            instanceof
            indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport
                .ResourceChangedException)
          return open(identifier, headers, publicOnly, floatSupported, multichannelSupported, true);
      }
      throw failure;
    }
  }

  private static AudioDecoder open(
      String identifier,
      Map<String, String> headers,
      boolean publicOnly,
      boolean floatSupported,
      boolean multichannelSupported,
      boolean singleResponse)
      throws IOException {
    return open(identifier, headers, publicOnly, floatSupported, multichannelSupported,
        singleResponse, null);
  }

  private static AudioDecoder open(
      String identifier, Map<String, String> headers, boolean publicOnly,
      boolean floatSupported, boolean multichannelSupported, boolean singleResponse,
      MediaTransport.ExpectedRepresentation expected) throws IOException {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    JavaAudioResource resource =
        new JavaAudioResource(identifier, headers, publicOnly, singleResponse, expected);
    try {
      JavaAudioResource.Lease lease = resource.lease();
      JavaAudioProbe.PreparedInput prepared = JavaAudioProbe.prepare(lease.stream());
      if (playlist(prepared.container())) {
        return resolvePlaylist(
            resource,
            lease,
            prepared,
            sourceUri(identifier),
            headers,
            publicOnly,
            singleResponse,
            floatSupported,
            multichannelSupported);
      }
      return assemble(resource, lease, prepared, floatSupported, multichannelSupported);
    } catch (Throwable error) {
      try {
        resource.close();
      } catch (IOException close) {
        error.addSuppressed(close);
      }
      throw error;
    }
  }

  private static AudioDecoder assemble(
      JavaAudioResource resource,
      JavaAudioResource.Lease lease,
      JavaAudioProbe.PreparedInput prepared,
      boolean floatSupported,
      boolean multichannelSupported)
      throws IOException {
    try {
      JavaAudioProbe.Container container = prepared.container();
      AudioDecoder nativePcm =
          switch (container) {
            case WAV -> new WavPcmDecoder(prepared.input(), floatSupported, multichannelSupported);
            case FLAC ->
                new FlacPcmDecoder(prepared.input(), floatSupported, multichannelSupported);
            default -> null;
          };
      if (nativePcm != null) return managed(nativePcm, resource, container);
      Factory factory = new Factory(resource, container);
      Opened first = decode(lease, prepared);
      factory.first.set(first.decoder());
      factory.track = first.track();
      factory.openEndedFinalEdit = first.openEndedFinalEdit();
      factory.targetRate = first.decoder().format().sampleRate();
      if (first.decoder() instanceof PacketMediaDecoder packet)
        factory.aacExpectation = packet.aacExpectation();
      PresentationPlan plan =
          first.track() == null || container == JavaAudioProbe.Container.MATROSKA
              ? new PresentationPlan(
                  List.of(new PresentationPlan.Segment(0, Long.MAX_VALUE)), Long.MAX_VALUE)
              : PresentationPlan.from(
                  first.track(),
                  first.decoder().format().sampleRate(),
                  false,
                  first.openEndedFinalEdit());
      PresentedDecoder.Factory attempts =
          container == JavaAudioProbe.Container.OGG && !resource.isIcy()
              ? new OggPlaybackFactory(resource, factory, factory.targetRate)
              : factory;
      if (container == JavaAudioProbe.Container.MPEG_AUDIO && !resource.isIcy())
        attempts = new Mp3PlaybackFactory(resource, factory);
      PresentedDecoder presented = new PresentedDecoder(attempts, plan);
      try {
        return managed(
            new JavaStreamDecoder(
                presented,
                floatSupported,
                multichannelSupported,
                SpeakerOrder.forFormat(presented.format())),
            resource,
            container,
            first.codec());
      } catch (Throwable error) {
        presented.close();
        throw error;
      }
    } catch (Throwable error) {
      try {
        resource.close();
      } catch (IOException close) {
        error.addSuppressed(close);
      }
      throw error;
    }
  }

  private static URI sourceUri(String identifier) {
    if (identifier.regionMatches(true, 0, "http://", 0, 7)
        || identifier.regionMatches(true, 0, "https://", 0, 8)
        || identifier.regionMatches(true, 0, "file:", 0, 5)) return URI.create(identifier);
    if (identifier.regionMatches(true, 0, "icy://", 0, 6))
      return URI.create("http://" + identifier.substring(6));
    return java.nio.file.Path.of(identifier).toAbsolutePath().toUri();
  }

  private static boolean playlist(JavaAudioProbe.Container container) {
    return container == JavaAudioProbe.Container.M3U_CANDIDATE
        || container == JavaAudioProbe.Container.PLS_CANDIDATE
        || container == JavaAudioProbe.Container.URL_PLAYLIST_CANDIDATE;
  }

  private record Prepared(
      JavaAudioResource resource,
      JavaAudioResource.Lease lease,
      JavaAudioProbe.PreparedInput input) {}

  private static AudioDecoder resolvePlaylist(
      JavaAudioResource resource,
      JavaAudioResource.Lease lease,
      JavaAudioProbe.PreparedInput prepared,
      URI original,
      Map<String, String> headers,
      boolean publicOnly,
      boolean singleResponse,
      boolean floating,
      boolean multichannel)
      throws IOException {
    URI root = lease.finalUri();
    MediaHeaderScope scope = new MediaHeaderScope(original, headers);
    AtomicReference<Prepared> first =
        new AtomicReference<>(new Prepared(resource, lease, prepared));
    AtomicReference<Prepared> media = new AtomicReference<>();
    try {
      var target =
          PlaylistResolver.resolve(
              root,
              publicOnly,
              (uri, policy) -> {
                Prepared current = first.getAndSet(null);
                if (current == null) {
                  var owner =
                      new JavaAudioResource(
                          uri.toString(), scope.forUri(uri), policy, singleResponse);
                  try {
                    var input = owner.lease();
                    current = new Prepared(owner, input, JavaAudioProbe.prepare(input.stream()));
                  } catch (Throwable error) {
                    try {
                      owner.close();
                    } catch (IOException close) {
                      error.addSuppressed(close);
                    }
                    throw error;
                  }
                }
                try {
                  URI actual = current.lease().finalUri();
                  if (!playlist(current.input().container())) {
                    media.set(current);
                    return new PlaylistResolver.Loaded(
                        actual, PlaylistResolver.Kind.MEDIA, new byte[0]);
                  }
                  byte[] body = current.input().input().readNBytes(1048577);
                  if (body.length > 1048576) throw new IOException("Playlist exceeds 1 MiB");
                  var kind =
                      current.input().container() == JavaAudioProbe.Container.PLS_CANDIDATE
                          ? PlaylistResolver.Kind.PLS
                          : PlaylistResolver.Kind.M3U;
                  return new PlaylistResolver.Loaded(actual, kind, body);
                } finally {
                  if (media.get() != current) current.resource().close();
                }
              });
      if (target.kind() == PlaylistResolver.Kind.HLS) {
        var factory =
            new HlsPlaybackFactory(target.uri(), target.publicOnly(), scope, target.playlist());
        var firstDecoder = factory.prime();
        PresentationPlan plan =
            PresentationPlan.from(firstDecoder.track(), firstDecoder.format().sampleRate(), false);
        PresentedDecoder presented;
        try {
          presented = new PresentedDecoder(factory, plan);
        } catch (Throwable error) {
          firstDecoder.close();
          throw error;
        }
        try {
          return new ManagedAudioDecoder(
              new JavaStreamDecoder(presented, floating, multichannel),
              () -> {},
              FormatType.GENERIC,
              "java/hls");
        } catch (Throwable error) {
          presented.close();
          throw error;
        }
      }
      Prepared resolved = media.getAndSet(null);
      if (resolved == null) throw new IOException("Playlist has no media resource");
      return assemble(
          resolved.resource(), resolved.lease(), resolved.input(), floating, multichannel);
    } finally {
      Prepared unused = first.getAndSet(null);
      if (unused != null) unused.resource().close();
      unused = media.getAndSet(null);
      if (unused != null) unused.resource().close();
    }
  }

  private record Opened(
      PcmDecoder decoder, PacketSource.Track track, String codec, boolean openEndedFinalEdit) {}

  private static Opened decode(JavaAudioResource.Lease lease, JavaAudioProbe.PreparedInput prepared)
      throws IOException {
    return decode(lease, prepared, -1, 0, null, 0);
  }

  private static Opened decode(
      JavaAudioResource.Lease lease,
      JavaAudioProbe.PreparedInput prepared,
      long requestedFrame,
      int targetRate,
      AacPacketDecoder.Expectation aacExpectation,
      int prerollSeconds)
      throws IOException {
    PcmDecoder decoder;
    String codec = null;
    PacketSource packets = null;
    switch (prepared.container()) {
      case MPEG_AUDIO -> decoder = JavaCodecs.mp3(prepared.input());
      case OGG -> {
        var links = JavaCodecs.oggLinks(prepared.input());
        String[] selected = {null};
        decoder =
            new StreamMediaDecoder(
                new StreamMediaDecoder.Input() {
                  public PcmDecoder nextLink() throws IOException {
                    var link = links.nextLink();
                    if (link != null && selected[0] == null) selected[0] = link.codec();
                    return link;
                  }

                  public void close() throws IOException {
                    links.close();
                  }
                });
        codec = selected[0];
      }
      case WAVPACK -> decoder = JavaCodecs.wavpack(prepared.input());
      case ASF -> decoder = JavaCodecs.wma(prepared.input());
      case APE -> decoder = JavaCodecs.ape(prepared.input());
      case AIFF, AU -> decoder = JavaCodecs.aiffAu(prepared.input());
      case ADTS -> {
        packets = new AdtsPacketSource(prepared.input());
        decoder = null;
      }
      case MPEG_TS -> {
        var adts =
            new indi.mopelotus.musichud.audio.container.aac.TsAdtsPacketSource(
                new indi.mopelotus.musichud.audio.container.ts.TsPacketSource(prepared.input()));
        packets =
            new NormalizedPacketSource(adts, adts::timelineEpoch, new PacketSource.Time(1, 90000));
        decoder = null;
      }
      case ISO_BMFF -> {
        var channel = lease.channel();
        channel.position(0);
        var views = new SharedChannelViews(channel);
        packets = new Mp4PacketSource(views.metadata(), views.media());
        decoder = null;
      }
      case MATROSKA -> {
        var channel = lease.channel();
        channel.position(0);
        packets = new MatroskaPacketSource(new ChannelSeekableInput(channel));
        decoder = null;
      }
      default -> throw new IOException("Unsupported audio container " + prepared.container());
    }
    if (packets != null) {
      try {
        long beginning = 0;
        if (requestedFrame >= 0
            && packets instanceof Mp4PacketSource mp4
            && (packets.track().initialFormat().codec().equals("alac") || aacExpectation != null)) {
          var first =
              mp4.seekMedia(
                  new PacketSource.Time(
                      Math.max(
                          0,
                          requestedFrame
                              - (aacExpectation == null
                                  ? 4096L
                                  : (long) prerollSeconds * targetRate)),
                      targetRate));
          if (first == null) throw new EOFException("MP4 seek beyond media extent");
          beginning = Math.max(0, PresentationPlan.framesCeil(first, targetRate));
        }
        AacRecoveryWindow recoveryWindow =
            aacExpectation == null
                ? null
                : new AacRecoveryWindow(
                    packets,
                    new PacketSource.Time(requestedFrame - (targetRate + 3L) / 4, targetRate));
        decoder =
            packets instanceof MatroskaPacketSource matroska
                ? new PacketMediaDecoder(
                    packets, 0, QuantizedPcmClock.matroska(matroska.timestampQuantum())::map)
                : new PacketMediaDecoder(
                    recoveryWindow == null ? packets : recoveryWindow,
                    targetRate,
                    beginning,
                    p -> p,
                    aacExpectation);
        if (recoveryWindow != null) recoveryWindow.release();
      } catch (Throwable error) {
        try {
          packets.close();
        } catch (IOException close) {
          error.addSuppressed(close);
        }
        throw error;
      }
    }
    return new Opened(
        decoder,
        packets == null ? null : packets.track(),
        codec,
        packets instanceof Mp4PacketSource mp4 && mp4.hasOpenEndedFinalEdit());
  }

  private static final class Factory implements PresentedDecoder.Factory {
    private final JavaAudioResource resource;
    private final JavaAudioProbe.Container expected;
    private PacketSource.Track track;
    private boolean openEndedFinalEdit;
    private int targetRate;
    private AacPacketDecoder.Expectation aacExpectation;
    private final AtomicReference<PcmDecoder> first = new AtomicReference<>();

    Factory(JavaAudioResource resource, JavaAudioProbe.Container expected) {
      this.resource = resource;
      this.expected = expected;
    }

    public boolean supportsFastSeek() {
      return expected == JavaAudioProbe.Container.ISO_BMFF
          && resource.rangeStable()
          && track != null
          && (track.initialFormat().codec().equals("alac") || aacExpectation != null);
    }

    public PresentedDecoder.Attempt attempt() {
      return attemptAtFrame(-1);
    }

    public PresentedDecoder.Attempt attemptAtFrame(long requested) {
      return new PresentedDecoder.Attempt() {
        private JavaAudioResource.Lease lease;
        private PcmDecoder result;
        private boolean cancelled;

        public PcmDecoder open() throws IOException {
          synchronized (this) {
            if (cancelled) throw new IOException("Decoder attempt cancelled");
            result = first.getAndSet(null);
            if (result != null) return result;
            lease = resource.lease();
          }
          Opened decoded = null;
          if (expected == JavaAudioProbe.Container.ISO_BMFF
              && requested >= targetRate / 4L
              && aacExpectation != null
              && resource.rangeStable()) {
            for (int preroll : new int[] {2, 4, 8}) {
              try {
                decoded = decodeAttempt(requested, aacExpectation, preroll);
                break;
              } catch (AacRecoveryWindow.Unavailable unavailable) {
                lease.close();
                synchronized (this) {
                  if (cancelled) throw new IOException("AAC recovery cancelled");
                  lease = resource.lease();
                }
              }
            }
          }
          if (decoded == null)
            decoded = decodeAttempt(aacExpectation == null ? requested : -1, null, 0);
          if (!Objects.equals(track, decoded.track())
              || openEndedFinalEdit != decoded.openEndedFinalEdit()) {
            decoded.decoder().close();
            throw new IOException("Reopened container timing changed");
          }
          PcmDecoder opened = decoded.decoder();
          synchronized (this) {
            if (!cancelled) {
              result = opened;
              return opened;
            }
          }
          opened.close();
          throw new IOException("Stale decoder attempt");
        }

        private Opened decodeAttempt(
            long requestedFrame, AacPacketDecoder.Expectation expectedAac, int preroll)
            throws IOException {
          var prepared = JavaAudioProbe.prepare(lease.stream());
          if (prepared.container() != expected)
            throw new IOException("Reopened audio container changed");
          return decode(lease, prepared, requestedFrame, targetRate, expectedAac, preroll);
        }

        public void cancel() {
          JavaAudioResource.Lease source;
          PcmDecoder decoder;
          synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            source = lease;
            decoder = result;
            result = null;
          }
          // Cancel input before waiting for codec locks, so a new seek never waits on old IO.
          if (source != null)
            try {
              source.close();
            } catch (IOException ignored) {
            }
          if (decoder != null)
            try {
              decoder.close();
            } catch (IOException ignored) {
            }
        }
      };
    }
  }

  private static AudioDecoder managed(
      AudioDecoder decoder, AutoCloseable resource, JavaAudioProbe.Container container) {
    return managed(decoder, resource, container, null);
  }

  private static AudioDecoder managed(
      AudioDecoder decoder,
      AutoCloseable resource,
      JavaAudioProbe.Container container,
      String codec) {
    FormatType type =
        switch (container) {
          case WAV -> FormatType.WAV;
          case FLAC -> FormatType.FLAC;
          case MPEG_AUDIO -> FormatType.MP3;
          case OGG -> "opus".equals(codec) ? FormatType.OPUS : FormatType.OGG;
          case ISO_BMFF -> FormatType.M4A;
          case ADTS -> FormatType.AAC;
          case AIFF -> FormatType.AIFF;
          case AU -> FormatType.AU;
          default -> FormatType.GENERIC;
        };
    return new ManagedAudioDecoder(
        decoder, resource, type, "java/" + container.name().toLowerCase(Locale.ROOT));
  }
}
