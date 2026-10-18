package indi.mopelotus.musichud.audio.container;

import org.mp4parser.AbstractBoxParser;
import org.mp4parser.ParsableBox;
import org.mp4parser.boxes.UnknownBox;
import org.mp4parser.boxes.iso14496.part12.*;
import org.mp4parser.boxes.sampleentry.AudioSampleEntry;

/**
 * Audio packet boxes use public constructors; unrelated metadata stays an opaque bounded extent.
 */
final class Mp4BoxParser extends AbstractBoxParser {
  @Override
  public ParsableBox createBox(String type, byte[] userType, String parent) {
    if ("stsd".equals(parent)
        && (type.equals("mp4a") || type.equals("alac") || type.equals(".mp3")))
      return new AudioSampleEntry(type);
    if (type.equals("alac") && "alac".equals(parent)) return new UnknownBox("alac");
    return switch (type) {
      case "moov" -> new MovieBox();
      case "mvhd" -> new MovieHeaderBox();
      case "trak" -> new TrackBox();
      case "tkhd" -> new TrackHeaderBox();
      case "mdia" -> new MediaBox();
      case "mdhd" -> new MediaHeaderBox();
      case "hdlr" -> new HandlerBox();
      case "minf" -> new MediaInformationBox();
      case "stbl" -> new SampleTableBox();
      case "stsd" -> new SampleDescriptionBox();
      case "stsz" -> new SampleSizeBox();
      case "stts" -> new TimeToSampleBox();
      case "ctts" -> new CompositionTimeToSample();
      case "stsc" -> new SampleToChunkBox();
      case "stco" -> new StaticChunkOffsetBox();
      case "co64" -> new ChunkOffset64BitBox();
      case "edts" -> new EditBox();
      case "elst" -> new EditListBox();
      case "mvex" -> new MovieExtendsBox();
      case "trex" -> new TrackExtendsBox();
      case "moof" -> new MovieFragmentBox();
      case "traf" -> new TrackFragmentBox();
      case "tfhd" -> new TrackFragmentHeaderBox();
      case "tfdt" -> new TrackFragmentBaseMediaDecodeTimeBox();
      case "trun" -> new TrackRunBox();
      case "esds" -> new Mp4PacketSource.RawDescriptorBox();
      default -> new Mp4PacketSource.SkippedPayload(type);
    };
  }
}
