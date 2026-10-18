package indi.mopelotus.musichud.audio.container.matroska;

import indi.mopelotus.musichud.audio.container.matroska.format.*;
import indi.mopelotus.musichud.audio.container.io.SeekableInputStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handles processing an MKV/WEBM file for the purpose of streaming one specific track from it. Only performs seeks when
 * absolutely necessary, as the stream may be a network connection, in which case each seek may require a new connection.
 */
public class MatroskaStreamingFile {
    private final MatroskaFileReader reader;

    private String title;
    private String artist;
    private String isrc;

    private long timecodeScale = 1000000;
    private double duration;
    private final ArrayList<MatroskaFileTrack> trackList = new ArrayList<>();
    private MatroskaElement segmentElement = null;
    private MatroskaElement firstClusterElement = null;

    private long minimumTimecode = 0;
    private boolean seeking = false;
    private long requestedTimecode;

    private Long cueElementPosition = null;
    private List<MatroskaCuePoint> cuePoints = null;

    /**
     * @param inputStream The input stream for the file
     */
    public MatroskaStreamingFile(SeekableInputStream inputStream) {
        this.reader = new MatroskaFileReader(inputStream);
    }

    /**
     * @return Timescale for the durations used in this file
     */
    public long getTimecodeScale() {
        return timecodeScale;
    }

    /**
     * @return The title for this file.
     */
    public String getTitle() {
        return title != null && title.isEmpty() ? null : title;
    }

    public String getArtist() {
        return artist != null && artist.isEmpty() ? null : artist;
    }

    public String getIsrc() {
        return isrc != null && isrc.isEmpty() ? null : isrc;
    }

    /**
     * @return Total duration of the file
     */
    public double getDuration() {
        return duration;
    }

    /**
     * @return Array of tracks in this file
     */
    public MatroskaFileTrack[] getTrackList() {
        if (!trackList.isEmpty()) {
            MatroskaFileTrack[] tracks = new MatroskaFileTrack[trackList.size()];

            for (int t = 0; t < trackList.size(); t++) {
                tracks[t] = trackList.get(t);
            }
            return tracks;
        } else {
            return new MatroskaFileTrack[0];
        }
    }

    /**
     * Read the headers and tracks from the file.
     *
     * @throws IOException On read error.
     */
    public void readFile() throws IOException {
        MatroskaElement ebmlElement = reader.readNextElement(null);
        if (ebmlElement == null) {
            throw new RuntimeException("Unable to scan for EBML elements");
        }

        if (ebmlElement.is(MatroskaElementType.Ebml)) {
            parseEbmlElement(ebmlElement);
        } else {
            throw new RuntimeException("EBML Header not the first element in the file");
        }

        segmentElement = reader.readNextElement(null).frozen();

        if (segmentElement.is(MatroskaElementType.Segment)) {
            parseSegmentElement(segmentElement);
        } else {
            throw new RuntimeException(String.format("Segment not the second element in the file: was %s (%d) instead",
                segmentElement.getType().name(), segmentElement.getId()));
        }
    }

    private void parseEbmlElement(MatroskaElement ebmlElement) throws IOException {
        MatroskaElement child;

        while ((child = reader.readNextElement(ebmlElement)) != null) {
            if (child.is(MatroskaElementType.DocType)) {
                String docType = reader.asString(child);

                if (docType.compareTo("matroska") != 0 && docType.compareTo("webm") != 0) {
                    throw new RuntimeException("Error: DocType is not matroska, \"" + docType + "\"");
                }
            }

            reader.skip(child);
        }
    }

    private void parseSegmentElement(MatroskaElement segmentElement) throws IOException {
        MatroskaElement child;

        while ((child = reader.readNextElement(segmentElement)) != null) {
            if (child.is(MatroskaElementType.Info)) {
                parseSegmentInfo(child);
            } else if (child.is(MatroskaElementType.Tags)) {
                parseTags(child);
            } else if (child.is(MatroskaElementType.Tracks)) {
                parseTracks(child);
            } else if (child.is(MatroskaElementType.Cluster)) {
                firstClusterElement = child.frozen();
                break;
            } else if (child.is(MatroskaElementType.SeekHead)) {
                parseSeekInfoForCuePosition(child);
            } else if (child.is(MatroskaElementType.Cues)) {
                cuePoints = parseCues(child);
            }

            reader.skip(child);
        }
    }

    private void parseSeekInfoForCuePosition(MatroskaElement seekHeadElement) throws IOException {
        MatroskaElement child;

        while ((child = reader.readNextElement(seekHeadElement)) != null) {
            if (child.is(MatroskaElementType.Seek)) {
                parseSeekElement(child);
            }

            reader.skip(child);
        }
    }

    private void parseSeekElement(MatroskaElement seekElement) throws IOException {
        MatroskaElement child;
        boolean isCueElement = false;

        while ((child = reader.readNextElement(seekElement)) != null) {
            if (child.is(MatroskaElementType.SeekId)) {
                isCueElement = ByteBuffer.wrap(reader.asBytes(child)).equals(ByteBuffer.wrap(MatroskaElementType.Cues.bytes));
            } else if (child.is(MatroskaElementType.SeekPosition) && isCueElement) {
                cueElementPosition = reader.asLong(child);
            }

            reader.skip(child);
        }
    }

    private List<MatroskaCuePoint> parseCues(MatroskaElement cuesElement) throws IOException {
        List<MatroskaCuePoint> parsedCuePoints = new ArrayList<>();
        MatroskaElement child;

        while ((child = reader.readNextElement(cuesElement)) != null) {
            if (child.is(MatroskaElementType.CuePoint)) {
                MatroskaCuePoint cuePoint = parseCuePoint(child);

                if (cuePoint != null) {
                    long budget=Math.max(64*1024,Math.min(8L*1024*1024,Runtime.getRuntime().maxMemory()/32));
                    if(parsedCuePoints.size()>=budget/128)throw new IOException("Cue index exceeds memory budget");
                    if(cuePoint.timecode<0 || (!parsedCuePoints.isEmpty() && cuePoint.timecode<parsedCuePoints.get(parsedCuePoints.size()-1).timecode))throw new IOException("Cue timecodes are not ordered");
                    parsedCuePoints.add(cuePoint);
                }
            }

            reader.skip(child);
        }

        return parsedCuePoints.isEmpty() ? null : parsedCuePoints;
    }

    private MatroskaCuePoint parseCuePoint(MatroskaElement cuePointElement) throws IOException {
        MatroskaElement child;

        Long cueTime = null;
        java.util.Map<Integer,Long> positions = null;

        while ((child = reader.readNextElement(cuePointElement)) != null) {
            if (child.is(MatroskaElementType.CueTime)) {
                cueTime = reader.asLong(child);
            } else if (child.is(MatroskaElementType.CueTrackPositions)) {
                positions = parseCueTrackPositions(child);
            }

            reader.skip(child);
        }

        if (cueTime != null && positions != null) {
            return new MatroskaCuePoint(cueTime, positions);
        } else {
            return null;
        }
    }

    private java.util.Map<Integer,Long> parseCueTrackPositions(MatroskaElement positionsElement) throws IOException {
        Long currentTrackId = null;
        MatroskaElement child;

        java.util.Map<Integer,Long> positions = new java.util.HashMap<>();

        while ((child = reader.readNextElement(positionsElement)) != null) {
            if (child.is(MatroskaElementType.CueTrack)) {
                currentTrackId = reader.asLong(child);
            } else if (child.is(MatroskaElementType.CueClusterPosition) && currentTrackId != null) {
                long offset=reader.asLong(child);
                if(currentTrackId>0 && currentTrackId<=Integer.MAX_VALUE) { int id=currentTrackId.intValue();if(trackList.stream().anyMatch(t->t.index==id))positions.put(id,offset); }
            }

            reader.skip(child);
        }

        return positions;
    }

    /**
     * Perform a seek to a specified timecode
     *
     * @param trackId  ID of the reference track
     * @param timecode Timecode using the timescale of the file
     */
    public void seekToTimecode(int trackId, long timecode) {
        try {
            seekToTimecodeInternal(trackId, timecode);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void seekToTimecodeInternal(int trackId, long timecode) throws IOException {
        if(timecode<0)throw new IOException("Negative seek");
        requestedTimecode=timecode;
        var selected=trackList.stream().filter(t->t.index==trackId).findFirst().orElseThrow(()->new IOException("Unknown track"));
        long prerollTicks=selected.seekPreRollNs/timecodeScale+(selected.seekPreRollNs%timecodeScale==0?0:1);
        timecode=Math.max(0,timecode-prerollTicks);
        minimumTimecode = timecode;
        seeking = true;

        if (cuePoints == null && cueElementPosition != null) {
            reader.seek(segmentElement.getDataPosition() + cueElementPosition);

            MatroskaElement cuesElement = reader.readNextElement(segmentElement);
            if (!cuesElement.is(MatroskaElementType.Cues)) {
                throw new IllegalStateException("The element here should be cue.");
            }

            cuePoints = parseCues(cuesElement);
        }

        if (cuePoints != null) {
            MatroskaCuePoint cuePoint = lastCueNotAfterTimecode(timecode);

            if (cuePoint != null && cuePoint.trackClusterOffsets.getOrDefault(trackId,-1L) >= 0) {
                reader.seek(segmentElement.getDataPosition() + cuePoint.trackClusterOffsets.get(trackId));
                return;
            }
        }

        // If there were no cues available, just seek to the beginning and discard until the right timecode
        reader.seek(firstClusterElement.getPosition());
    }

    private MatroskaCuePoint lastCueNotAfterTimecode(long timecode) {
        int largerTimecodeIndex;

        for (largerTimecodeIndex = 0; largerTimecodeIndex < cuePoints.size(); largerTimecodeIndex++) {
            if (cuePoints.get(largerTimecodeIndex).timecode > timecode) {
                break;
            }
        }

        if (largerTimecodeIndex > 0) {
            return cuePoints.get(largerTimecodeIndex - 1);
        } else {
            return null;
        }
    }

    /**
     * Provide data chunks for the specified track consumer
     *
     * @param consumer Track data consumer
     * @throws InterruptedException When interrupted externally (or for seek/stop).
     */
    public void provideFrames(MatroskaTrackConsumer consumer) throws InterruptedException {
        try {
            long position = reader.getPosition();
            MatroskaElement child = position == firstClusterElement.getDataPosition()
                ? firstClusterElement : reader.readNextElement(segmentElement);

            while (child != null) {
                if (child.is(MatroskaElementType.Cluster)) {
                    parseNextCluster(child, consumer);
                    if(child.isUnknownSize())child.resolveUnknownEnd(reader.getPosition());
                }

                reader.skip(child);

                if (segmentElement.getRemaining(reader.getPosition()) <= 0) {
                    break;
                }

                child = reader.readNextElement(segmentElement);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void parseNextCluster(MatroskaElement clusterElement, MatroskaTrackConsumer consumer) throws InterruptedException, IOException {
        MatroskaElement child;
        long clusterTimecode = 0;

        while ((child = reader.readNextElement(clusterElement)) != null) {
            if (child.is(MatroskaElementType.Timecode)) {
                clusterTimecode = reader.asLong(child);
                if(clusterTimecode<0)throw new IOException("Negative cluster timecode");
            } else if (child.is(MatroskaElementType.SimpleBlock)) {
                parseClusterSimpleBlock(child, consumer, clusterTimecode);
            } else if (child.is(MatroskaElementType.BlockGroup)) {
                parseClusterBlockGroup(child, consumer, clusterTimecode);
            }

            reader.skip(child);
        }
    }

    private void parseClusterSimpleBlock(MatroskaElement simpleBlock, MatroskaTrackConsumer consumer, long clusterTimecode)
        throws InterruptedException, IOException {

        MatroskaBlock block = reader.readBlockHeader(simpleBlock, consumer.getTrack().index);

        if (block != null) {
            processFrameInBlock(block, consumer, clusterTimecode,0,0);
        }
    }

    private void parseClusterBlockGroup(MatroskaElement group, MatroskaTrackConsumer consumer,long clusterTimecode)
        throws InterruptedException, IOException {
        MatroskaElement child, savedBlock=null;long padding=0,durationTicks=0;
        while((child=reader.readNextElement(group))!=null) {
            if(child.is(MatroskaElementType.Block)){if(savedBlock!=null)throw new IOException("Multiple Blocks in BlockGroup");savedBlock=child.frozen();}
            else if(child.is(MatroskaElementType.DiscardPadding))padding=reader.asLong(child);
            else if(child.is(MatroskaElementType.BlockDuration))durationTicks=reader.asLong(child);
            reader.skip(child);
        }
        long end=reader.getPosition();
        if(savedBlock!=null){reader.seek(savedBlock.getDataPosition());MatroskaBlock block=reader.readBlockHeader(savedBlock,consumer.getTrack().index);if(block!=null)processFrameInBlock(block,consumer,clusterTimecode,padding,Math.multiplyExact(durationTicks,timecodeScale));}
        reader.seek(end);
    }

    private void processFrameInBlock(MatroskaBlock block, MatroskaTrackConsumer consumer, long clusterTimecode,long paddingNs,long blockDurationNs)
        throws InterruptedException, IOException {

        long timecode = clusterTimecode + block.getTimecode();

        if (timecode >= minimumTimecode) {
            int frameCount = block.getFrameCount();

            if (seeking) {
                consumer.seekPerformed(requestedTimecode, timecode);
                seeking = false;
            }

            for (int i = 0; i < frameCount; i++) {
                long blockTimestampNs=Math.multiplyExact(timecode,timecodeScale);
                long frameTimestampNs=blockTimestampNs;
                if(consumer.getTrack().defaultDurationNs>0)frameTimestampNs=Math.addExact(frameTimestampNs,Math.multiplyExact(i,consumer.getTrack().defaultDurationNs));
                long presentationNs=Math.subtractExact(frameTimestampNs,consumer.getTrack().codecDelayNs);
                consumer.consumeFrame(block.getNextFrameBuffer(reader, i),new MatroskaTrackConsumer.PacketTiming(
                    blockTimestampNs,presentationNs,blockDurationNs,(paddingNs<0?i==0:i==frameCount-1)?paddingNs:0,i,frameCount));
            }
        }
    }

    private void parseSegmentInfo(MatroskaElement infoElement) throws IOException {
        MatroskaElement child;

        while ((child = reader.readNextElement(infoElement)) != null) {
            if (child.is(MatroskaElementType.Duration)) {
                duration = reader.asDouble(child);
                if(!Double.isFinite(duration)||duration<0)throw new IOException("Invalid Matroska duration");
            } else if (child.is(MatroskaElementType.TimecodeScale)) {
                timecodeScale = reader.asLong(child);
                if(timecodeScale<=0)throw new IOException("Invalid Matroska timescale");
            } else if (child.is(MatroskaElementType.Title) && title == null) {
                title = reader.asString(child, StandardCharsets.UTF_8);
            }

            reader.skip(child);
        }
    }

    private void parseTracks(MatroskaElement tracksElement) throws IOException {
        MatroskaElement child;

        while ((child = reader.readNextElement(tracksElement)) != null) {
            if (child.is(MatroskaElementType.TrackEntry)) {
                if(trackList.size()>=256)throw new IOException("Too many Matroska tracks");
                var parsed=MatroskaFileTrack.parse(child, reader);
                if(parsed.index<1)throw new IOException("Track number exceeds bound");
                if(trackList.stream().anyMatch(t->t.index==parsed.index))throw new IOException("Duplicate track number");
                trackList.add(parsed);
            }

            reader.skip(child);
        }
    }

    private void parseTags(MatroskaElement tagsElement) throws IOException {
        MatroskaElement child;

        while ((child = reader.readNextElement(tagsElement)) != null) {
            if (child.is(MatroskaElementType.Tag)) {
                parseTag(child);
            }

            reader.skip(child);
        }
    }

    private void parseTag(MatroskaElement tagElement) throws IOException {
        MatroskaElement child;

        while ((child = reader.readNextElement(tagElement)) != null) {
            if (child.is(MatroskaElementType.SimpleTag)) {
                parseSimpleTag(child);
            }

            reader.skip(child);
        }
    }

    private void parseSimpleTag(MatroskaElement simpleTagElement) throws IOException {
        MatroskaElement child;
        String tagName = null;

        while ((child = reader.readNextElement(simpleTagElement)) != null) {
            if (child.is(MatroskaElementType.TagName)) {
                tagName = reader.asString(child);
            } else if (child.is(MatroskaElementType.TagString)) {
                // https://www.matroska.org/technical/tagging.html
                if ("title".equalsIgnoreCase(tagName) && title == null) {
                    title = reader.asString(child, StandardCharsets.UTF_8);
                } else if ("artist".equalsIgnoreCase(tagName)) {
                    artist = reader.asString(child, StandardCharsets.UTF_8);
                } else if ("isrc".equalsIgnoreCase(tagName)) {
                    // probably not necessary to force a charset here
                    isrc = reader.asString(child, StandardCharsets.UTF_8);
                }
            }

            reader.skip(child);
        }
    }
    /** A pull cursor shares this file reader; do not use provideFrames concurrently. */
    public PacketCursor packetCursor(int trackId) throws IOException {
        var track=trackList.stream().filter(t->t.index==trackId).findFirst().orElseThrow(()->new IOException("Unknown track"));
        return new PacketCursor(track);
    }
    public record CompressedPacket(ByteBuffer encoded,MatroskaTrackConsumer.PacketTiming timing) {}
    public final class PacketCursor implements java.io.Closeable {
        public final MatroskaFileTrack track;
        private MatroskaElement cluster,blockElement;
        private MatroskaBlock block;
        private long clusterTime,blockEnd,padding,blockDuration;
        private int lace;
        private volatile boolean closed;
        PacketCursor(MatroskaFileTrack track) throws IOException {this.track=track;resetAtFirstCluster();}
        private void resetAtFirstCluster() throws IOException {
            if(firstClusterElement==null)throw new IOException("No Matroska cluster");
            reader.seek(firstClusterElement.getDataPosition());cluster=firstClusterElement.frozen();clusterTime=0;block=null;
        }
        public CompressedPacket next() throws IOException {
            if(closed)throw new IOException("Cursor closed");
            try {
                for(;;) {
                    if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Matroska read interrupted");
                    if(block!=null) {
                        int i=lace++;var buffer=block.getNextFrameBuffer(reader,i);byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);
                        long timestamp=Math.multiplyExact(Math.addExact(clusterTime,block.getTimecode()),timecodeScale);
                        long presentation=Math.subtractExact(timestamp,track.codecDelayNs);
                        if(track.defaultDurationNs>0)presentation=Math.addExact(presentation,Math.multiplyExact(i,track.defaultDurationNs));
                        var timing=new MatroskaTrackConsumer.PacketTiming(timestamp,presentation,blockDuration,
                            (padding<0?i==0:i==block.getFrameCount()-1)?padding:0,i,block.getFrameCount());
                        if(lace==block.getFrameCount()){reader.seek(blockEnd);block=null;}
                        if(closed)throw new IOException("Cursor closed during read");
                        return new CompressedPacket(ByteBuffer.wrap(bytes).asReadOnlyBuffer(),timing);
                    }
                    if(cluster==null) {
                        MatroskaElement child=reader.readNextElement(segmentElement);if(child==null)return null;
                        if(!child.is(MatroskaElementType.Cluster)){reader.skip(child);continue;}
                        cluster=child.frozen();clusterTime=0;
                    }
                    MatroskaElement child=reader.readNextElement(cluster);
                    if(child==null){if(cluster.isUnknownSize())cluster.resolveUnknownEnd(reader.getPosition());reader.skip(cluster);cluster=null;continue;}
                    if(child.is(MatroskaElementType.Timecode)){clusterTime=reader.asLong(child);if(clusterTime<0)throw new IOException("Negative cluster timecode");reader.skip(child);continue;}
                    if(child.is(MatroskaElementType.SimpleBlock)) {
                        blockElement=child.frozen();blockEnd=child.getDataPosition()+child.getDataSize();padding=blockDuration=0;
                    } else if(child.is(MatroskaElementType.BlockGroup)) {
                        MatroskaElement part;blockElement=null;padding=blockDuration=0;
                        while((part=reader.readNextElement(child))!=null) {
                            if(part.is(MatroskaElementType.Block)){if(blockElement!=null)throw new IOException("Multiple Blocks in group");blockElement=part.frozen();}
                            else if(part.is(MatroskaElementType.DiscardPadding))padding=reader.asLong(part);
                            else if(part.is(MatroskaElementType.BlockDuration))blockDuration=Math.multiplyExact(reader.asLong(part),timecodeScale);
                            reader.skip(part);
                        }
                        blockEnd=reader.getPosition();if(blockElement==null)continue;reader.seek(blockElement.getDataPosition());
                    } else {reader.skip(child);continue;}
                    block=reader.readBlockHeader(blockElement,track.index);lace=0;
                    if(block==null)reader.seek(blockEnd);
                }
            } catch(ArithmeticException|IllegalArgumentException|IllegalStateException e){throw new IOException("Invalid Matroska packet",e);}
        }
        /** Positions before requested presentation time including declared decoder preroll. Caller trims decoded PCM. */
        public void seekPresentationNs(long ns) throws IOException {
            if(closed)throw new IOException("Cursor closed");if(ns<0)throw new IOException("Negative presentation seek");
            long mediaNs;try{mediaNs=Math.addExact(ns,track.codecDelayNs);}catch(ArithmeticException e){throw new IOException("Seek overflow",e);}
            seekToTimecodeInternal(track.index,mediaNs/timecodeScale);
            cluster=null;block=null;clusterTime=0;lace=0;
        }
        @Override public synchronized void close() throws IOException {if(closed)return;closed=true;reader.close();}
    }

}
