/* Controlled Java translation of FFmpeg n9.0.2 libavformat/ape.c and
 * libavcodec/apedec.c. Copyright (c) 2007 Benjamin Zores, based on
 * libdemac by Dave Chapman. Java translation 2026. LGPL-2.1-or-later.
 * See upstream/COPYING.LGPLv2.1. Modern >=3950 paths only, research prototype.
 */
package indi.mopelotus.musichud.audio.codec.internal.ape;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.zip.CRC32;
/** Bounded seekable APE prototype. Returns source-depth interleaved PCM little endian.
 * 8-bit PCM is unsigned. 16/24-bit PCM is signed. One verified APE frame per read.
 * Constructor owns the channel even if parsing fails; close is idempotent.
 */
public final class ApeDecoder implements AutoCloseable {
    public static final int MAX_FRAMES=1_000_000,MAX_FRAME_BYTES=32*1024*1024,MAX_BLOCKS=1_179_648;
    private final ApeInput input;
    private final Path temporary;
    private final long length;
    private long extent=-1;
    private final int wavTailBytes;
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
    public final int version,compression,bits,channels,sampleRate,blocksPerFrame;
    public final long totalSamples;
    private final int finalBlocks;
    private final long[] positions;
    private final int[] sizes,skips;
    private int nextFrame,skipSamples;
    private int interimMode;
    public static ApeDecoder open(Path path)throws IOException {return new ApeDecoder(ApeInput.file(path),null);}
    /** Bounded preparation for nonseekable sources. Never stores an unbounded source in heap.
     * The stream is owned and closed on success/failure. Temporary storage is deleted at close.
     */
    public static ApeDecoder open(InputStream source,long maxBytes)throws IOException {
        if(source==null)throw new NullPointerException("source");
        if(maxBytes<52){source.close();throw new IOException("APE spool bound too small");}
        Path temp;
        try{temp=Files.createTempFile("java-ape-", ".spool");}catch(IOException|RuntimeException failure){try{source.close();}catch(IOException close){failure.addSuppressed(close);}throw failure;}
        boolean success=false;
        try {
            try(InputStream in=source;OutputStream out=Files.newOutputStream(temp)) {
                byte[] buf=new byte[16384];long written=0;int zero=0;
                while(true){checkInterrupted();int n=in.read(buf);if(n==-1)break;if(n==0){if(++zero>100)throw new IOException("APE source made no progress");continue;}zero=0;if(n>maxBytes-written)throw new IOException("APE source exceeds spool bound");out.write(buf,0,n);written+=n;}
            }
            ApeDecoder decoder=new ApeDecoder(ApeInput.file(temp),temp);success=true;return decoder;
        }finally{if(!success)Files.deleteIfExists(temp);}
    }
    public static ApeDecoder open(ApeInput source)throws IOException{return new ApeDecoder(java.util.Objects.requireNonNull(source),null);}
    public static ApeDecoder openForwardStream(InputStream source)throws IOException{return open(ApeInput.forward(source));}
    public static ApeDecoder openForwardStream(InputStream source,long declaredLength)throws IOException{return open(ApeInput.forward(source,declaredLength));}
    public boolean seekable(){return input.seekable();}
    private ApeDecoder(ApeInput channel,Path temporary)throws IOException {
        this.input=channel;this.temporary=temporary;
        try {
            length=channel.length();if(length< -1)throw new IOException("Invalid APE input length");long bound=length>=0?length:Long.MAX_VALUE;long base=0;byte[] prefix=readAt(0,6);
            if(prefix[0]=='I'&&prefix[1]=='D'&&prefix[2]=='3'){
                int id3Version=prefix[3]&255,revision=prefix[4]&255,flags=prefix[5]&255;
                int allowed=id3Version==2?0xc0:id3Version==3?0xe0:id3Version==4?0xf0:0;
                if(allowed==0||revision==255||(flags&~allowed)!=0)throw new IOException("Invalid leading ID3 header");
                byte[] size=readAt(6,4);long tagBytes=0;
                for(byte value:size){if(value<0)throw new IOException("Invalid ID3 synchsafe size");tagBytes=(tagBytes<<7)|(value&127);}
                base=10+tagBytes+((id3Version==4&&(flags&16)!=0)?10:0);
                if(base>16*1024*1024||base>bound-6)throw new IOException("Leading ID3 tag exceeds bounded extent");
                prefix=readAt(base,6);
            }
            if(prefix[0]!='M'||prefix[1]!='A'||prefix[2]!='C'||prefix[3]!=' ')throw new IOException("APE magic mismatch");
            version=(prefix[4]&255)|((prefix[5]&255)<<8);
            if(version<3950||version>3990)throw new IOException("Research APE version not implemented: "+version);
            long descriptorLength,headerLength,seekLength,wavLength,audioLength,wavTail,seekPosition;
            int frames;
            if(version>=3980){
                byte[] descriptor=new byte[52];System.arraycopy(prefix,0,descriptor,0,6);System.arraycopy(readAt(base+6,46),0,descriptor,6,46);
                ByteBuffer d=ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN);
                descriptorLength=u32(d,8);headerLength=u32(d,12);seekLength=u32(d,16);wavLength=u32(d,20);audioLength=u32(d,24)|(u32(d,28)<<32);wavTail=u32(d,32);
                if(descriptorLength<52||headerLength<24||seekLength%4!=0||descriptorLength>32L*1024*1024||headerLength>32L*1024*1024||seekLength>32L*1024*1024||wavLength>32L*1024*1024||descriptorLength>bound-base||headerLength>bound-base-descriptorLength)throw new IOException("Invalid APE descriptor lengths");
                ByteBuffer h=ByteBuffer.wrap(readAt(base+descriptorLength,24)).order(ByteOrder.LITTLE_ENDIAN);
                compression=Short.toUnsignedInt(h.getShort(0));
                blocksPerFrame=checkedInt(u32(h,4),1,MAX_BLOCKS,"blocks per frame");
                finalBlocks=checkedInt(u32(h,8),1,blocksPerFrame,"final blocks");
                frames=checkedInt(u32(h,12),1,MAX_FRAMES,"frames");
                bits=Short.toUnsignedInt(h.getShort(16));channels=Short.toUnsignedInt(h.getShort(18));sampleRate=checkedInt(u32(h,20),1,768000,"sample rate");
                seekPosition=base+descriptorLength+headerLength;
            }else{
                byte[] header=new byte[32];System.arraycopy(prefix,0,header,0,6);System.arraycopy(readAt(base+6,26),0,header,6,26);
                ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                descriptorLength=0;headerLength=32;audioLength=0;
                compression=Short.toUnsignedInt(h.getShort(6));int flags=Short.toUnsignedInt(h.getShort(8));
                channels=Short.toUnsignedInt(h.getShort(10));sampleRate=checkedInt(u32(h,12),1,768000,"sample rate");
                wavLength=u32(h,16);wavTail=u32(h,20);frames=checkedInt(u32(h,24),1,MAX_FRAMES,"frames");
                blocksPerFrame=73728*4;finalBlocks=checkedInt(u32(h,28),1,blocksPerFrame,"final blocks");
                if((flags&4)!=0){readAt(base+headerLength,4);headerLength+=4;}
                if((flags&16)!=0){seekLength=u32(ByteBuffer.wrap(readAt(base+headerLength,4)).order(ByteOrder.LITTLE_ENDIAN),0)*4;headerLength+=4;}else seekLength=(long)frames*4;
                bits=(flags&1)!=0?8:(flags&8)!=0?24:16;
                seekPosition=base+headerLength+((flags&32)==0?wavLength:0);
            }
            if((bits!=8&&bits!=16&&bits!=24)||(channels!=1&&channels!=2)||compression<1000||compression>5000||compression%1000!=0)throw new IOException("Unsupported or invalid APE format");
            if(seekLength<(long)frames*4||seekLength>bound-base-descriptorLength-headerLength)throw new IOException("Invalid APE seek table length");
            long first=base+descriptorLength+headerLength+seekLength+wavLength;
            if(first<0||first>=bound||audioLength<0||audioLength>bound-first||wavTail>bound-first)throw new IOException("Invalid APE audio extent");
            if(first-base>32L*1024*1024)throw new IOException("APE prefix exceeds bound");
            wavTailBytes=checkedInt(wavTail,0,16*1024*1024,"wav tail bytes");
            if(audioLength>0)extent=first+audioLength;
            byte[] seek=readAt(seekPosition,Math.multiplyExact(frames,4));ByteBuffer s=ByteBuffer.wrap(seek).order(ByteOrder.LITTLE_ENDIAN);
            positions=new long[frames];sizes=new int[frames];skips=new int[frames];
            long prev=-1;
            for(int i=0;i<frames;i++){
                long position=base+Integer.toUnsignedLong(s.getInt());
                if((i==0&&position!=first)||position<=prev||position>=(extent>=0?extent:bound))throw new IOException("Invalid APE seek entry");
                positions[i]=position;skips[i]=(int)((position-first)&3);prev=position;
            }
            long audioEnd=audioLength>0?first+audioLength:length>=0?length-wavTail:-1;
            for(int i=0;i<frames;i++){
                long end=i+1<frames?positions[i+1]:audioEnd;
                if(end<0){positions[i]-=skips[i];sizes[i]=-1;continue;}
                long size=end-positions[i];if(audioLength==0&&i==frames-1)size&=~3L;size+=skips[i];positions[i]-=skips[i];
                size=(size+3)&~3L;
                sizes[i]=checkedInt(size,4,MAX_FRAME_BYTES,"frame bytes");
                if(positions[i]+size>(extent>=0?extent:bound))throw new IOException("Truncated APE final word");
            }
            totalSamples=(long)(frames-1)*blocksPerFrame+finalBlocks;
            interimMode=bits==24?-1:0;
        } catch(Throwable ex){try{channel.close();}catch(IOException close){ex.addSuppressed(close);}if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException close){ex.addSuppressed(close);}throw ex;}
    }
    static long u32(ByteBuffer b,int offset){return Integer.toUnsignedLong(b.getInt(offset));}
    static int checkedInt(long value,int min,int max,String label)throws IOException{if(value<min||value>max)throw new IOException("Invalid APE "+label+": "+value);return(int)value;}
    static void checkInterrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("APE decode interrupted");}
    private void ensureOpen()throws IOException{if(closed.get())throw new IOException("APE decoder closed");checkInterrupted();}
    private byte[] readAt(long offset,int size)throws IOException{
        ensureOpen();long available=extent>=0?extent:input.length();if(offset<0||size<0||(available>=0&&offset>available-size))throw new EOFException("APE read outside input extent");
        byte[] bytes=new byte[size];input.readFully(offset,bytes);ensureOpen();return bytes;
    }
    public synchronized void seekToSample(long sample)throws IOException{
        ensureOpen();if(!input.seekable())throw new IOException("Source does not support random APE seeks");if(sample<0||sample>totalSamples)throw new IOException("APE seek outside sample extent");
        if(sample==totalSamples){nextFrame=positions.length;skipSamples=0;return;}
        nextFrame=(int)(sample/blocksPerFrame);skipSamples=(int)(sample%blocksPerFrame);interimMode=bits==24?-1:0;
    }
    private static boolean magic(byte[] b,int offset,String text){if(offset<0||offset>b.length-text.length())return false;for(int i=0;i<text.length();i++)if((b[offset+i]&255)!=text.charAt(i))return false;return true;}
    /** Only strips bounded known trailing structures; it does not interpret tag fields. */
    private static byte[] trimUnknownFrameTail(byte[] raw,int wavTail)throws IOException{
        int end=raw.length;
        if(end>=128&&magic(raw,end-128,"TAG"))end-=128;
        if(end>=32&&magic(raw,end-32,"APETAGEX")){
            ByteBuffer footer=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);int start=end-32;
            long version=u32(footer,start+8),tagSize=u32(footer,start+12),fields=u32(footer,start+16),flags=u32(footer,start+20);
            if(version>2000||tagSize<32||tagSize>16*1024*1024||tagSize>end||fields>65536||(flags&0x60000000L)!=0)throw new IOException("Invalid bounded trailing APE tag");
            end-=(int)tagSize;
            if((flags&0x80000000L)!=0){
                if(end<32||!magic(raw,end-32,"APETAGEX")||u32(footer,end-24)!=version||u32(footer,end-20)!=tagSize||(u32(footer,end-12)&0x20000000L)==0)throw new IOException("Invalid trailing APE tag header");
                end-=32;
            }
        }
        if(wavTail>end)throw new IOException("Truncated trailing WAV data");end-=wavTail;end&=~3;
        if(end<8)throw new IOException("APE final frame too short");
        return java.util.Arrays.copyOf(raw,end);
    }
    /** Null at EOF. Frame CRC is validated before any bytes are returned. */
    public synchronized byte[] readFrame()throws IOException {
        ensureOpen();if(nextFrame==positions.length)return null;
        int frame=nextFrame,blocks=frame==positions.length-1?finalBlocks:blocksPerFrame;
        byte[] bytes;
        if(sizes[frame]<0){
            ensureOpen();bytes=input.readToEnd(positions[frame],MAX_FRAME_BYTES);ensureOpen();
            bytes=trimUnknownFrameTail(bytes,wavTailBytes);
        }else bytes=readAt(positions[frame],sizes[frame]);
        for(int i=0;i<bytes.length;i+=4){byte a=bytes[i],b=bytes[i+1];bytes[i]=bytes[i+3];bytes[i+1]=bytes[i+2];bytes[i+2]=b;bytes[i+3]=a;}
        ApeEntropy entropy=new ApeEntropy(bytes,skips[frame],version);ApePredictor predictor=new ApePredictor(compression,version,bits);predictor.interimMode=interimMode;
        boolean mono=channels==1||(entropy.flags&4)!=0;
        boolean silence=mono?(entropy.flags&3)!=0:(entropy.flags&3)==3;
        byte[] pcm=new byte[Math.multiplyExact(blocks,channels*(bits/8))];int out=0;
        int[][] decoded=new int[][]{new int[4608],new int[4608]};
        for(int block=0;block<blocks;block+=4608){
            ensureOpen();int count=Math.min(4608,blocks-block);
            java.util.Arrays.fill(decoded[0],0);java.util.Arrays.fill(decoded[1],0);
            if(!silence){entropy.entropy_decode(decoded,count,mono);if(mono){predictor.predictor_decode_mono_3950(decoded,count);if(channels==2)System.arraycopy(decoded[0],0,decoded[1],0,count);}else{
                predictor.predictor_decode_stereo_3950(decoded,count);
                for(int i=0;i<count;i++){int left=decoded[1][i]-decoded[0][i]/2;decoded[1][i]=left+decoded[0][i];decoded[0][i]=left;}
            }}
            for(int i=0;i<count;i++)for(int ch=0;ch<channels;ch++){
                int sample=decoded[ch][i];if(bits==8)sample+=128;
                for(int b=0;b<bits/8;b++)pcm[out++]=(byte)(sample>>>(b*8));
            }
        }
        CRC32 crc=new CRC32();crc.update(pcm);
        if((crc.getValue()>>>1)!=entropy.crc)throw new IOException("APE frame CRC mismatch at "+frame+": "+Long.toHexString(crc.getValue()>>>1)+" != "+Long.toHexString(entropy.crc));
        ensureOpen();interimMode=predictor.interimMode;nextFrame++;
        if(skipSamples!=0){int skip=skipSamples*channels*(bits/8);skipSamples=0;return java.util.Arrays.copyOfRange(pcm,skip,pcm.length);}
        return pcm;
    }
    @Override public void close()throws IOException{
        if(!closed.compareAndSet(false,true))return;IOException failure=null;
        try{input.close();}catch(IOException ex){failure=ex;}
        if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ex){if(failure==null)failure=ex;else failure.addSuppressed(ex);}
        if(failure!=null)throw failure;
    }
}
