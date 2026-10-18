package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.beans.music.FormatType;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the complete playback resource and keeps probe metadata from the original opened bytes. */
final class ManagedAudioDecoder implements AudioDecoder {
  private final AudioDecoder decoder;
  private final AutoCloseable resource;
  private final FormatType detected;
  private final String backend;
  private final AtomicBoolean closed = new AtomicBoolean();

  ManagedAudioDecoder(
      AudioDecoder decoder, AutoCloseable resource, FormatType detected, String backend) {
    this.decoder = decoder;
    this.resource = resource;
    this.detected = detected;
    this.backend = backend;
  }

  FormatType detectedFormat() {
    return detected;
  }

  String backendName() {
    return backend;
  }

  public byte[] readChunk(long size) {
    return closed.get() ? null : decoder.readChunk(size);
  }

  public boolean supportsFastSeek() {
    return !closed.get() && decoder.supportsFastSeek();
  }

  public boolean seekToFrame(long frame) {
    return !closed.get() && decoder.seekToFrame(frame);
  }

  public long getPositionFrames() {
    return decoder.getPositionFrames();
  }

  public boolean seekToMillis(long millis) {
    return !closed.get() && decoder.seekToMillis(millis);
  }

  public long getPositionMillis() {
    return decoder.getPositionMillis();
  }

  public int getFormat() {
    return decoder.getFormat();
  }

  public int getSampleRate() {
    return decoder.getSampleRate();
  }

  public int getFrameSize() {
    return decoder.getFrameSize();
  }

  public SampleEncoding getSampleEncoding() {
    return decoder.getSampleEncoding();
  }

  public boolean hasDownmixedChannels() {
    return decoder.hasDownmixedChannels();
  }

  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    Throwable failure = null;
    try {
      decoder.close();
    } catch (Throwable error) {
      failure = error;
    }
    try {
      resource.close();
    } catch (Throwable error) {
      if (failure == null) failure = error;
      else failure.addSuppressed(error);
    }
    if (failure instanceof RuntimeException error) throw error;
    if (failure instanceof Error error) throw error;
    if (failure != null)
      throw new UncheckedIOException(new IOException("Unable to close media resource", failure));
  }
}
