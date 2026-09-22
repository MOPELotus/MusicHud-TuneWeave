package indi.mopelotus.musichud.client.services.cloud;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.ui.dto.CloudEntryState;
import indi.mopelotus.musichud.interfaces.Unregister;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Upstream sequential upload queue adapted to client-owned TuneWeave account scopes. */
public final class CloudUploadService {
    public static final long COMPLETION_PRESENTATION_MILLIS = 2000;
    private static final class Holder { static final CloudUploadService INSTANCE = new CloudUploadService(); }
    public static CloudUploadService getInstance() { return Holder.INSTANCE; }

    interface PreparedUpload {
        String run(LongConsumer progress, Runnable publishing, BooleanSupplier cancelled);
    }
    interface Preparation { PreparedUpload prepare(CloudUploadTask task); }
    private record TransferCallbacks(LongConsumer progress, Runnable publishing, BooleanSupplier cancelled) {}
    private final Executor executor;
    private final Supplier<Object> accountScope;
    private final Preparation preparation;
    private final Consumer<CloudUploadTask> metadata;
    private final AtomicLong lastProgressNotification = new AtomicLong();
    private final Map<Long, CloudUploadTask> tasks = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong(-1);
    private boolean running;
    private CloudUploadTask active;
    private Thread worker;

    private CloudUploadService() {
        this(MusicHud.EXECUTOR, MusicEntityCache::captureGeneration, task -> {
            var service = TuneWeaveClientService.getInstance();
            var call = service.<TransferCallbacks, String>prepareFunction(callbacks -> service.uploadCloudTrack(
                    task.getFile(), task.getSong(), task.getArtist(), task.getAlbum(), task.getBitrate(),
                    callbacks.progress(), callbacks.publishing(), callbacks.cancelled()));
            return (progress, publishing, cancelled) -> call.apply(new TransferCallbacks(progress, publishing, cancelled));
        }, CloudUploadService::prepareMetadata);
    }

    CloudUploadService(Executor executor, Supplier<Object> accountScope, Preparation preparation,
                       Consumer<CloudUploadTask> metadata) {
        this.executor = executor;
        this.accountScope = accountScope;
        this.preparation = preparation;
        this.metadata = metadata;
    }

    public CloudUploadTask enqueue(Path file) {
        var task = new CloudUploadTask(ids.getAndDecrement(), file);
        task.setAccountScope(accountScope.get());
        // Pin the selected account before entering the background queue.
        task.setPreparedUpload(preparation.prepare(task));
        synchronized (this) { discardOldAccounts(); tasks.put(task.getId(), task); }
        changed();
        ensureWorker();
        return task;
    }

    public synchronized List<CloudUploadTask> snapshot() {
        discardOldAccounts();
        return List.copyOf(tasks.values());
    }

    public Unregister addOnChange(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void cancel(long id) {
        synchronized (this) {
            CloudUploadTask task = tasks.get(id);
            if (task == null || (task.getState() != CloudEntryState.QUEUED
                    && task.getState() != CloudEntryState.UPLOADING)) return;
            task.setCancelRequested(true);
            task.setState(CloudEntryState.CANCELLED);
            if (active == task && worker != null) worker.interrupt();
        }
        changed();
    }

    public void retry(long id) {
        synchronized (this) {
            CloudUploadTask task = tasks.get(id);
            if (task == null || task.getAccountScope() != accountScope.get()
                    || (task.getState() != CloudEntryState.FAILED && task.getState() != CloudEntryState.CANCELLED)) return;
            if (active == task) {
                task.setRetryRequested(true);
                return;
            }
            task.setPrepared(false);
            task.setCancelRequested(false);
            task.setErrorMessage("");
            task.setBytesUploaded(0);
            task.setState(CloudEntryState.QUEUED);
        }
        changed();
        ensureWorker();
    }

    public void remove(long id) {
        synchronized (this) {
            CloudUploadTask task = tasks.get(id);
            if (task == null || task.getState() == CloudEntryState.MATCHING) return;
            tasks.remove(id);
            task.setCancelRequested(true);
            if (active == task && worker != null) worker.interrupt();
        }
        changed();
    }

    public void markPresented(long id) {
        synchronized (this) {
            CloudUploadTask task = tasks.get(id);
            if (task == null || task.getState() != CloudEntryState.COMPLETED) return;
            task.setState(CloudEntryState.COMPLETED_PRESENTED);
        }
        changed();
    }

    public void reconcileReferences(Set<String> references) {
        synchronized (this) {
            tasks.values().removeIf(task -> task.getState() == CloudEntryState.COMPLETED_PRESENTED
                    && references.contains(task.getResolvedTrackId()));
        }
    }

    private boolean cancelled(CloudUploadTask task) {
        return task.isCancelRequested() || task.getAccountScope() != accountScope.get();
    }

    private void discardOldAccounts() {
        Object scope = accountScope.get();
        tasks.values().removeIf(task -> {
            if (task.getAccountScope() == scope) return false;
            task.setCancelRequested(true);
            if (active == task && worker != null) worker.interrupt();
            return true;
        });
    }

    private void ensureWorker() {
        synchronized (this) {
            if (running) return;
            running = true;
        }
        try {
            executor.execute(this::runQueue);
        } catch (RuntimeException failure) {
            synchronized (this) {
                running = false;
                for (var task : tasks.values()) {
                    if (task.getState() == CloudEntryState.QUEUED) {
                        task.setState(CloudEntryState.FAILED);
                        task.setErrorMessage("Upload worker could not start");
                    }
                }
            }
            changed();
        }
    }

    private void runQueue() {
        try {
            while (true) {
                CloudUploadTask task;
                synchronized (this) {
                    discardOldAccounts();
                    task = tasks.values().stream().filter(t -> t.getState() == CloudEntryState.QUEUED).findFirst().orElse(null);
                    if (task == null) return;
                    active = task;
                    worker = Thread.currentThread();
                    task.setAttempt(task.getAttempt() + 1);
                    task.setState(CloudEntryState.UPLOADING);
                }
                changed();
                try {
                    if (!task.isPrepared()) metadata.accept(task);
                    if (cancelled(task)) throw new CancellationException();
                    long attempt = task.getAttempt();
                    String reference = task.getPreparedUpload().run(bytes -> {
                        synchronized (this) {
                            if (cancelled(task) || task.getAttempt() != attempt || active != task) return;
                            task.setBytesUploaded(bytes);
                        }
                        long now = System.currentTimeMillis();
                        long previous = lastProgressNotification.get();
                        if ((bytes >= task.getFileSize() || now - previous >= 200)
                                && lastProgressNotification.compareAndSet(previous, now)) changed();
                    }, () -> {
                        synchronized (this) {
                            if (cancelled(task) || task.getAttempt() != attempt || active != task) throw new CancellationException();
                            task.setState(CloudEntryState.MATCHING);
                        }
                        changed();
                    }, () -> cancelled(task) || task.getAttempt() != attempt);
                    synchronized (this) {
                        if (!cancelled(task) && tasks.get(task.getId()) == task) {
                            task.setResolvedTrackId(reference);
                            task.setState(CloudEntryState.COMPLETED);
                        }
                    }
                } catch (Exception error) {
                    synchronized (this) {
                        if (tasks.get(task.getId()) == task) {
                            task.setState(cancelled(task) ? CloudEntryState.CANCELLED : CloudEntryState.FAILED);
                            // Provider exceptions already redact headers and upload URLs.
                            task.setErrorMessage(Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName()));
                        }
                    }
                } finally {
                    Thread.interrupted();
                    synchronized (this) {
                        active = null;
                        worker = null;
                        if (task.isRetryRequested() && tasks.get(task.getId()) == task
                                && task.getAccountScope() == accountScope.get()) {
                            task.setRetryRequested(false);
                            task.setPrepared(false);
                            task.setCancelRequested(false);
                            task.setErrorMessage("");
                            task.setBytesUploaded(0);
                            task.setState(CloudEntryState.QUEUED);
                        }
                    }
                    changed();
                }
            }
        } finally {
            boolean more;
            synchronized (this) {
                running = false;
                more = tasks.values().stream().anyMatch(t -> t.getState() == CloudEntryState.QUEUED);
            }
            if (more) ensureWorker();
        }
    }

    private void changed() {
        for (Runnable listener : listeners) {
            try { listener.run(); }
            catch (RuntimeException error) { MusicHud.LOGGER.debug("Cloud upload view listener failed", error); }
        }
    }

    private static void prepareMetadata(CloudUploadTask task) {
        if (!Files.isRegularFile(task.getFile())) throw new IllegalArgumentException("Cloud upload must be a file");
        try { task.setFileSize(Files.size(task.getFile())); }
        catch (java.io.IOException error) { throw new IllegalArgumentException("Cannot read upload file", error); }
        if (task.getFileSize() <= 0 || task.getFileSize() > 500L * 1024 * 1024)
            throw new IllegalArgumentException("Cloud upload file must be between 1 byte and 500 MiB");
        task.setSong(task.getFileName().replaceFirst("\\.[^.]+$", ""));
        try {
            var audio = AudioFileIO.read(task.getFile().toFile());
            var tag = audio.getTag();
            if (tag != null) {
                if (!tag.getFirst(FieldKey.TITLE).isBlank()) task.setSong(tag.getFirst(FieldKey.TITLE));
                task.setArtist(tag.getFirst(FieldKey.ARTIST));
                task.setAlbum(tag.getFirst(FieldKey.ALBUM));
                var art = tag.getFirstArtwork();
                if (art != null && art.getBinaryData() != null && art.getBinaryData().length <= 8 * 1024 * 1024) {
                    String mime = Objects.requireNonNullElse(art.getMimeType(), "image/jpeg");
                    if (mime.equals("image/png") || mime.equals("image/jpeg"))
                        task.setCoverDataUri("data:" + mime + ";base64," + Base64.getEncoder().encodeToString(art.getBinaryData()));
                }
            }
            var header = audio.getAudioHeader();
            if (header != null) {
                task.setDurationMillis((int) Math.clamp(Math.round(header.getPreciseTrackLength() * 1000), 0, Integer.MAX_VALUE));
                if (header.getBitRateAsNumber() > 0) task.setBitrate(header.getBitRateAsNumber() * 1000);
            }
        } catch (Exception ignored) {
            // The upload contract also accepts files without readable tags.
        }
        task.setPrepared(true);
    }
}
