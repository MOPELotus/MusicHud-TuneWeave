/* LGPL-2.1-or-later. Bounded Java input adapters for the controlled FFmpeg APE port. */
package indi.mopelotus.musichud.audio.codec.internal.ape;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
/** Position-addressed input boundary. Implementations must support close during a blocked read.
 * A transport can implement this interface with its existing validated Range/cancellation rules.
 * Ownership transfers to ApeDecoder.open, including when parsing fails.
 */
public interface ApeInput extends AutoCloseable {
 /** Returns -1 only for an unknown-length source. */
 long length() throws IOException;
 default byte[] readToEnd(long position,int maxBytes)throws IOException{throw new IOException("Source cannot provide bounded EOF read");}
 boolean seekable();
 void readFully(long position,byte[] target) throws IOException;
 @Override void close() throws IOException;
 static ApeInput file(Path path)throws IOException{return new FileInput(FileChannel.open(path,StandardOpenOption.READ));}
 static ApeInput forward(InputStream stream)throws IOException{return new ForwardInput(java.util.Objects.requireNonNull(stream),-1);}
 static ApeInput forward(InputStream stream,long length)throws IOException{
  if(stream==null)throw new NullPointerException("stream");
  if(length<52){stream.close();throw new IOException("Invalid declared APE stream length");}
  return new ForwardInput(stream,length);
 }
 final class FileInput implements ApeInput {
  private final FileChannel channel;
  FileInput(FileChannel channel){this.channel=channel;}
  public long length()throws IOException{return channel.size();}
  public boolean seekable(){return true;}
  public void readFully(long offset,byte[] bytes)throws IOException{
   ByteBuffer buffer=ByteBuffer.wrap(bytes);int zero=0;
   while(buffer.hasRemaining()){ApeDecoder.checkInterrupted();int n=channel.read(buffer,offset+buffer.position());if(n<0)throw new EOFException("Truncated APE input");if(n==0&&++zero>100)throw new IOException("APE input made no progress");if(n>0)zero=0;}
  }
  public void close()throws IOException{channel.close();}
 }
 final class ForwardInput implements ApeInput {
  private final InputStream input;
  private final long length;
  private final byte[] tail=new byte[4],skip=new byte[16384];
  private long position;
  private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
  ForwardInput(InputStream input,long length){this.input=input;this.length=length;}
  public long length(){return length;}
  public boolean seekable(){return false;}
  private void appendTail(byte[] data,int offset,int count){
   if(count>=4)System.arraycopy(data,offset+count-4,tail,0,4);
   else {System.arraycopy(tail,count,tail,0,4-count);System.arraycopy(data,offset,tail,4-count,count);}
  }
  private void read(byte[] bytes,int offset,int count)throws IOException{
   int done=0,zero=0;
   while(done<count){if(closed.get())throw new IOException("APE input closed");ApeDecoder.checkInterrupted();int n=input.read(bytes,offset+done,count-done);if(n<0)throw new EOFException("Truncated APE stream");if(n==0){if(++zero>100)throw new IOException("APE input made no progress");continue;}zero=0;appendTail(bytes,offset+done,n);done+=n;position+=n;}
  }
  public void readFully(long offset,byte[] bytes)throws IOException{
   if(closed.get())throw new IOException("APE input closed");
   if(offset<0||(length>=0&&offset>length-bytes.length))throw new EOFException("APE input exceeds declared length");
   if(offset<position-4)throw new IOException("Forward APE source cannot seek backwards");
   int done=0;
   if(offset<position){int cached=(int)Math.min(position-offset,bytes.length);System.arraycopy(tail,4-(int)(position-offset),bytes,0,cached);done=cached;}
   while(offset>position){int n=(int)Math.min(skip.length,offset-position);read(skip,0,n);}
   read(bytes,done,bytes.length-done);
  }
  public byte[] readToEnd(long offset,int maxBytes)throws IOException{
   if(closed.get())throw new IOException("APE input closed");
   if(maxBytes<0||offset<0)throw new IOException("Invalid bounded EOF read size or position");
   if(offset<position-4)throw new IOException("Forward APE source cannot seek backwards");
   while(offset>position){int n=(int)Math.min(skip.length,offset-position);read(skip,0,n);}
   ByteArrayOutputStream bytes=new ByteArrayOutputStream(Math.min(maxBytes,16384));
   if(offset<position){int cached=(int)(position-offset);if(cached>maxBytes)throw new IOException("APE cached tail exceeds EOF bound");bytes.write(tail,4-cached,cached);}
   byte[] part=new byte[16384];int zero=0;
   while(true){
    if(closed.get())throw new IOException("APE input closed");ApeDecoder.checkInterrupted();
    int n=input.read(part,0,(int)Math.min(part.length,(long)maxBytes-bytes.size()+1));
    if(n<0)return bytes.toByteArray();
    if(n==0){if(++zero>100)throw new IOException("APE input made no progress");continue;}
    zero=0;appendTail(part,0,n);position+=n;
    if(n>maxBytes-bytes.size())throw new IOException("APE final frame and trailing data exceed bound");
    bytes.write(part,0,n);
   }
  }
  public void close()throws IOException{if(!closed.compareAndSet(false,true))return;input.close();}
 }
}
