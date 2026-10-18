package indi.mopelotus.musichud.audio.container.matroska.format;

import indi.mopelotus.musichud.audio.container.io.SeekableInputStream;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Handles reading of elements and their content from an MKV file.
 */
public class MatroskaFileReader {
    private final SeekableInputStream inputStream;
    private final DataInput dataInput;
    private final MutableMatroskaElement[] levels;
    private final MutableMatroskaBlock mutableBlock;

    /**
     * @param inputStream Input stream to read from.
     */
    public MatroskaFileReader(SeekableInputStream inputStream) {
        this.inputStream = inputStream;
        this.dataInput = new DataInputStream(inputStream);
        this.levels = new MutableMatroskaElement[32];
        this.mutableBlock = new MutableMatroskaBlock();
    }

    /**
     * @param parent The parent element to use for bounds checking, null is valid.
     * @return The element whose header was read. Null if the parent/file has ended. The contents of this element are only
     * valid until the next element at the same level is read, use {@link MatroskaElement#frozen()} to get a
     * permanent instance.
     * @throws IOException On read error
     */
    public MatroskaElement readNextElement(MatroskaElement parent) throws IOException {
        long position = inputStream.getPosition();
        long remaining = parent != null ? parent.getRemaining(position) : inputStream.getContentLength() - position;

        if (remaining == 0) {
            return null;
        } else if (remaining < 0) {
            throw new IllegalStateException("Current position is beyond this element");
        }

        long id;MatroskaEbmlReader.Size encodedSize;
        try { id=MatroskaEbmlReader.readEbmlInteger(dataInput,null);encodedSize=MatroskaEbmlReader.readSize(dataInput); }
        catch(IllegalStateException e){throw new IOException("Invalid EBML header",e);}
        long dataSize = encodedSize.value();
        long dataPosition = inputStream.getPosition();

        var type = MatroskaElementType.find(id);
        if(parent != null && parent.isUnknownSize() && parent.is(MatroskaElementType.Cluster)
           && (type==MatroskaElementType.Cluster || type==MatroskaElementType.Info || type==MatroskaElementType.Tracks || type==MatroskaElementType.Cues || type==MatroskaElementType.Tags || type==MatroskaElementType.SeekHead)) {
            inputStream.seek(position);return null;
        }
        long limit=parent==null?inputStream.getContentLength():parent.getDataPosition()+parent.getDataSize();
        if(dataPosition>limit)throw new java.io.EOFException("Element header exceeds parent");
        if(encodedSize.unknown()) {
            if(type!=MatroskaElementType.Segment&&type!=MatroskaElementType.Cluster)throw new IOException("Unknown size only allowed for Segment/Cluster");
            dataSize=limit-dataPosition;
        }
        if(dataSize<0||dataSize>limit-dataPosition)throw new java.io.EOFException("Element payload exceeds parent/file");
        int level = parent == null ? 0 : parent.getLevel() + 1;
        if(level>=levels.length)throw new IOException("Matroska nesting exceeds bound");
        MutableMatroskaElement element = levels[level];

        if (element == null) {
            element = levels[level] = new MutableMatroskaElement(level);
        }

        element.setId(id);
        element.setType(MatroskaElementType.find(id));
        element.setPosition(position);
        element.setHeaderSize((int) (dataPosition - position));
        element.setDataSize(dataSize);
        element.setUnknownSize(encodedSize.unknown());
        return element;
    }

    /**
     * Reads one Matroska block header. The data is of the block is not read, but can be read frame by frame using
     * {@link MatroskaBlock#getNextFrameBuffer(MatroskaFileReader, int)}.
     *
     * @param parent      The block parent element.
     * @param trackFilter The ID of the track to read data for from the block.
     * @return An instance of a block if it contains data for the requested track, <code>null</code> otherwise.
     * @throws IOException On read error.
     */
    public MatroskaBlock readBlockHeader(MatroskaElement parent, int trackFilter) throws IOException {
        if (!mutableBlock.parseHeader(this, parent, trackFilter)) {
            return null;
        }

        return mutableBlock;
    }

    /**
     * @param element Element to read from
     * @return The contents of the element as an integer
     * @throws IOException On read error
     */
    public int asInteger(MatroskaElement element) throws IOException {
        if (element.is(MatroskaElementType.DataType.UNSIGNED_INTEGER)) {
            long value = MatroskaEbmlReader.readFixedSizeEbmlInteger(dataInput, integerSize(element), null);

            if (value < 0 || value > Integer.MAX_VALUE) {
                throw new ArithmeticException("Cannot convert unsigned value to integer.");
            } else {
                return (int) value;
            }
        } else if (element.is(MatroskaElementType.DataType.SIGNED_INTEGER)) {
            return Math.toIntExact(MatroskaEbmlReader.readFixedSizeEbmlInteger(dataInput, integerSize(element), MatroskaEbmlReader.Type.SIGNED));
        } else {
            throw new IllegalArgumentException("Not an integer element.");
        }
    }

    /**
     * @param element Element to read from
     * @return The contents of the element as a long
     * @throws IOException On read error
     */
    public long asLong(MatroskaElement element) throws IOException {
        if (element.is(MatroskaElementType.DataType.UNSIGNED_INTEGER)) {
            return MatroskaEbmlReader.readFixedSizeEbmlInteger(dataInput, integerSize(element), null);
        } else if (element.is(MatroskaElementType.DataType.SIGNED_INTEGER)) {
            return MatroskaEbmlReader.readFixedSizeEbmlInteger(dataInput, integerSize(element), MatroskaEbmlReader.Type.SIGNED);
        } else {
            throw new IllegalArgumentException("Not an integer element.");
        }
    }

    /**
     * @param element Element to read from
     * @return The contents of the element as a float
     * @throws IOException On read error
     */
    public float asFloat(MatroskaElement element) throws IOException {
        if (element.is(MatroskaElementType.DataType.FLOAT)) {
            if (element.dataSize == 4) {
                return dataInput.readFloat();
            } else if (element.dataSize == 8) {
                return (float) dataInput.readDouble();
            } else {
                throw new IllegalStateException("Float element has invalid size.");
            }
        } else {
            throw new IllegalArgumentException("Not a float element.");
        }
    }

    /**
     * @param element Element to read from
     * @return The contents of the element as a double
     * @throws IOException On read error
     */
    public double asDouble(MatroskaElement element) throws IOException {
        if (element.is(MatroskaElementType.DataType.FLOAT)) {
            if (element.dataSize == 4) {
                return dataInput.readFloat();
            } else if (element.dataSize == 8) {
                return dataInput.readDouble();
            } else {
                throw new IllegalStateException("Float element has invalid size.");
            }
        } else {
            throw new IllegalArgumentException("Not a float element.");
        }
    }

    private int integerSize(MatroskaElement e) throws IOException {
        if(e.dataSize<1||e.dataSize>8)throw new IOException("Invalid EBML integer width");
        return (int)e.dataSize;
    }

    public String asString(MatroskaElement element) throws IOException {
        return asString(element, null);
    }

    /**
     * @param element Element to read from
     * @param forceCharset The charset to use, or null for default.
     * @return The contents of the element as a string
     * @throws IOException On read error
     */
    public String asString(MatroskaElement element, Charset forceCharset) throws IOException {
        if (element.is(MatroskaElementType.DataType.STRING)) {
            return new String(asBytes(element), forceCharset != null ? forceCharset : StandardCharsets.US_ASCII);
        } else if (element.is(MatroskaElementType.DataType.UTF8_STRING)) {
            return new String(asBytes(element), forceCharset != null ? forceCharset : StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Not a string element.");
        }
    }

    /**
     * @param element Element to read from
     * @return The contents of the element as a byte array
     * @throws IOException On read error
     */
    public byte[] asBytes(MatroskaElement element) throws IOException {
        long budget=Math.max(65536,Math.min(8L*1024*1024,Runtime.getRuntime().maxMemory()/16));
        if(element.dataSize<0 || element.dataSize>budget || element.getRemaining(getPosition())!=element.dataSize)throw new IOException("Matroska payload exceeds allocation bound or is not at payload start");
        byte[] bytes = new byte[(int)element.dataSize];
        dataInput.readFully(bytes);
        return bytes;
    }

    /**
     * @param element Element to skip over
     * @throws IOException On read error
     */
    public void skip(MatroskaElement element) throws IOException {
        long remaining = element.getRemaining(inputStream.getPosition());

        if (remaining > 0) {
            inputStream.skipFully(remaining);
        } else if (remaining < 0) {
            throw new IllegalStateException("Current position is beyond this element");
        }
    }

    /**
     * @return Returns the current absolute position of the file.
     */
    public long getPosition() {
        return inputStream.getPosition();
    }

    /**
     * Seeks to the specified position.
     *
     * @param position The position in bytes.
     * @throws IOException On read error
     */
    public void seek(long position) throws IOException {
        inputStream.seek(position);
    }

    public void close() throws IOException { inputStream.close(); }

    public DataInput getDataInput() {
        return dataInput;
    }
}
