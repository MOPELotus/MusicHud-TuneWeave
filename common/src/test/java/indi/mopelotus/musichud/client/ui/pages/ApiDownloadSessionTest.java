package indi.mopelotus.musichud.client.ui.pages;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ApiDownloadSessionTest {
    @Test
    void downloadStateSurvivesDialogRecreationAndRejectsDuplicateStart() {
        ApiDownloadSession session = ApiDownloadSession.getInstance();
        session.reset();
        Path target = Path.of("build", "tuneweave");
        assertTrue(session.tryStart(target));
        assertFalse(session.tryStart(target));
        session.reportProgress(50, 100);
        assertEquals(ApiDownloadSession.Page.DOWNLOADING, session.snapshot().page());
        assertEquals(50, session.snapshot().downloaded());
        assertSame(target, session.snapshot().targetDir());
        session.cancel();
        assertEquals(ApiDownloadSession.Page.IDLE, session.snapshot().page());
    }

    @Test
    void listenersCanBeRemovedAndCancellationCancelsTheRunningFuture() {
        ApiDownloadSession session = ApiDownloadSession.getInstance();
        session.reset();
        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;
        session.addListener(listener);
        assertTrue(session.tryStart(Path.of("build", "tuneweave")));
        CompletableFuture<Void> future = new CompletableFuture<>();
        session.setFuture(future);
        session.removeListener(listener);
        session.cancel();
        assertTrue(future.isCancelled());
        assertEquals(1, notifications.get());
    }

    @Test
    void staleCompletionCannotReplaceAnewerDownload() {
        ApiDownloadSession session = ApiDownloadSession.getInstance();
        session.reset();
        assertTrue(session.tryStart(Path.of("build", "first")));
        long oldGeneration = session.generation();
        session.cancel();
        assertTrue(session.tryStart(Path.of("build", "second")));
        assertFalse(session.complete(oldGeneration,
                new indi.mopelotus.musichud.server.api.ApiBinaryUpdateService.DownloadedRelease(
                        "old", "old", Path.of("build", "first"))));
        assertEquals(Path.of("build", "second"), session.snapshot().targetDir());
        assertEquals(ApiDownloadSession.Page.DOWNLOADING, session.snapshot().page());
    }
}
