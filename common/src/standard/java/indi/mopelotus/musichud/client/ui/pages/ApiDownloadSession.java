package indi.mopelotus.musichud.client.ui.pages;

import indi.mopelotus.musichud.server.api.ApiBinaryUpdateService;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared lifecycle for the client-side TuneWeave binary download dialog. */
public final class ApiDownloadSession {
    public enum Page { IDLE, DOWNLOADING, DONE }

    public record Snapshot(Page page, long downloaded, long total, Path targetDir,
                           ApiBinaryUpdateService.DownloadedRelease release) {}

    private static final ApiDownloadSession INSTANCE = new ApiDownloadSession();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Page page = Page.IDLE;
    private long downloaded;
    private long total = -1;
    private Path targetDir;
    private ApiBinaryUpdateService.DownloadedRelease release;
    private CompletableFuture<?> future;
    private AtomicBoolean cancelled = new AtomicBoolean(false);
    private long generation;

    private ApiDownloadSession() {}

    public static ApiDownloadSession getInstance() { return INSTANCE; }

    public synchronized Snapshot snapshot() {
        return new Snapshot(page, downloaded, total, targetDir, release);
    }

    public synchronized boolean tryStart(Path targetDir) {
        if (page == Page.DOWNLOADING) return false;
        this.page = Page.DOWNLOADING;
        this.targetDir = targetDir;
        this.release = null;
        this.downloaded = 0;
        this.total = -1;
        this.future = null;
        this.cancelled = new AtomicBoolean(false);
        generation++;
        fire();
        return true;
    }

    public synchronized long generation() { return generation; }

    public synchronized AtomicBoolean cancelFlag() { return cancelled; }

    public synchronized void setFuture(CompletableFuture<?> future) { this.future = future; }

    public void reportProgress(long downloaded, long total) {
        synchronized (this) {
            if (page != Page.DOWNLOADING) return;
            this.downloaded = downloaded;
            this.total = total;
        }
        fire();
    }

    public void reportProgress(long downloaded, long total, long expectedGeneration) {
        synchronized (this) {
            if (generation != expectedGeneration || page != Page.DOWNLOADING) return;
            this.downloaded = downloaded;
            this.total = total;
        }
        fire();
    }

    public void complete(ApiBinaryUpdateService.DownloadedRelease release) {
        synchronized (this) {
            this.release = release;
            this.page = Page.DONE;
            this.future = null;
        }
        fire();
    }

    public boolean complete(long expectedGeneration, ApiBinaryUpdateService.DownloadedRelease release) {
        synchronized (this) {
            if (generation != expectedGeneration || page != Page.DOWNLOADING) return false;
            this.release = release;
            this.page = Page.DONE;
            this.future = null;
        }
        fire();
        return true;
    }

    public void fail() {
        synchronized (this) {
            this.page = Page.IDLE;
            this.release = null;
            this.future = null;
            this.downloaded = 0;
            this.total = -1;
        }
        fire();
    }

    public boolean fail(long expectedGeneration) {
        synchronized (this) {
            if (generation != expectedGeneration || page != Page.DOWNLOADING) return false;
            this.page = Page.IDLE;
            this.release = null;
            this.future = null;
            this.downloaded = 0;
            this.total = -1;
        }
        fire();
        return true;
    }

    public void cancel() {
        CompletableFuture<?> running;
        synchronized (this) {
            cancelled.set(true);
            running = future;
            generation++;
            page = Page.IDLE;
            future = null;
            release = null;
        }
        if (running != null) running.cancel(true);
        fire();
    }

    public void reset() {
        synchronized (this) {
            page = Page.IDLE;
            downloaded = 0;
            total = -1;
            targetDir = null;
            release = null;
            future = null;
            cancelled = new AtomicBoolean(false);
            generation++;
        }
        fire();
    }

    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    private void fire() { listeners.forEach(Runnable::run); }
}
