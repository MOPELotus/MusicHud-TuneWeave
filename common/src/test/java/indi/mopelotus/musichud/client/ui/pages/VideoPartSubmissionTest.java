package indi.mopelotus.musichud.client.ui.pages;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the production click handler, replacing only the game/UI host and queue environment. */
class VideoPartSubmissionTest {
    @Test void rejectedScopesNeverReachTheQueueAndLaterCurrentClicksStillWork() throws Exception {
        Page page = fixture(); Host host = (Host) page.getClass().getField("tuneWeave").get(page);
        var play = page.getClass().getMethod("play", Part.class);
        for (int i = 0; i < 20; i++) {
            host.failure = new CancellationException("Entity mapping scope changed");
            assertDoesNotThrow(() -> play.invoke(page, new Part()));
            assertEquals(0, page.submitted.size(), "a rejected scoped mapping must not queue or report success");
            assertNull(page.status, "scope cancellation must not publish stale status feedback");
        }
        host.failure = null;
        play.invoke(page, new Part());
        assertEquals(List.of(host.track), page.submitted);
        assertNull(page.status);
    }

    @Test void ordinaryMappingErrorsUseFailureFeedbackAndMissingVideoDoesNothing() throws Exception {
        Page page = fixture(); Host host = (Host) page.getClass().getField("tuneWeave").get(page);
        var play = page.getClass().getMethod("play", Part.class);
        host.failure = new IllegalArgumentException("Invalid video part");
        assertDoesNotThrow(() -> play.invoke(page, new Part()));
        assertEquals("Invalid video part", page.status); assertTrue(page.submitted.isEmpty());
        page.getClass().getField("video").set(page, null);
        int calls = host.calls;
        play.invoke(page, new Part()); assertEquals(calls, host.calls); assertTrue(page.submitted.isEmpty());
    }

    @Test void cancelledFirstBulkPartStopsTheOldActionAndAnIndependentClickCanRecover() throws Exception {
        Page page = fixture(); Host host = (Host) page.getClass().getField("tuneWeave").get(page);
        page.getClass().getField("parts").set(page, List.of(new Part(), new Part(), new Part()));
        host.failure = new CancellationException("Entity mapping scope changed");
        assertDoesNotThrow(() -> page.getClass().getMethod("playAll").invoke(page));
        assertEquals(1, host.calls, "bulk cancellation must not start a new mapping scope for later parts");
        assertTrue(page.submitted.isEmpty()); assertNull(page.status);
        host.failure = null;
        assertEquals(true, page.getClass().getMethod("play", Part.class).invoke(page, new Part()));
        assertEquals(List.of(host.track), page.submitted, "a later independent current click remains usable");
    }

    @Test void accountChangeAfterOneBulkSubmissionStopsBeforeMappingTheNextPart() throws Exception {
        Page page = fixture(); Host host = (Host) page.getClass().getField("tuneWeave").get(page);
        Tasks tasks = (Tasks) page.getClass().getField("tasks").get(page);
        page.getClass().getField("parts").set(page, List.of(new Part(), new Part(), new Part()));
        page.afterSubmit = () -> tasks.current = false;
        page.getClass().getMethod("playAll").invoke(page);
        assertEquals(1, host.calls, "each bulk part must remain in the original view/account binding");
        assertEquals(List.of(host.track), page.submitted);
    }

    public static class Page {
        final List<Track> submitted = new ArrayList<>(); String status; Runnable afterSubmit;
        public void showStatus(String text) { status = text; }
    }
    public static class Video {}
    public static class Part {}
    public static class Track {}
    public static class Host {
        RuntimeException failure; int calls; final Track track = new Track();
        public Track videoPartTrack(Video video, Part part) {
            calls++; if (failure != null) throw failure; return track;
        }
    }
    public static class Queue {
        public static void submit(Page page, Track track) {
            page.submitted.add(track); if (page.afterSubmit != null) page.afterSubmit.run();
        }
    }
    public static class Token {}
    public static class Tasks {
        final Token token = new Token(); boolean current = true;
        public Token capture() { return token; }
        public boolean isCurrent(Token value) { return current && value == token; }
    }

    private static Page fixture() throws Exception {
        String original = "indi/mopelotus/musichud/client/ui/pages/VideoDetailView";
        String target = original + "SubmissionFixture";
        ClassNode source = new ClassNode();
        try (var bytes = VideoPartSubmissionTest.class.getResourceAsStream("VideoDetailView.class")) {
            assertNotNull(bytes); new ClassReader(bytes).accept(source, 0);
        }
        String services = "indi/mopelotus/musichud/client/services/tuneweave/";
        Map<String,String> names = Map.of(
                original, target,
                source.superName, Type.getInternalName(Page.class),
                "icyllis/modernui/view/View", Type.getInternalName(Page.class),
                services + "TuneWeaveClientService", Type.getInternalName(Host.class),
                services + "TuneWeaveVideo", Type.getInternalName(Video.class),
                services + "TuneWeaveVideoPart", Type.getInternalName(Part.class),
                "indi/mopelotus/musichud/beans/music/MusicDetail", Type.getInternalName(Track.class),
                "indi/mopelotus/musichud/client/ui/QueueSubmissionFeedback", Type.getInternalName(Queue.class),
                "indi/mopelotus/musichud/client/ui/ScopedViewTasks", Type.getInternalName(Tasks.class),
                "indi/mopelotus/musichud/client/ui/ScopedViewTasks$Token", Type.getInternalName(Token.class));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        var mapped = new ClassRemapper(writer, new SimpleRemapper(names));
        mapped.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, original, null, source.superName, null);
        mapped.visitField(Opcodes.ACC_PUBLIC, "tuneWeave", "L"+services+"TuneWeaveClientService;", null, null).visitEnd();
        mapped.visitField(Opcodes.ACC_PUBLIC, "video", "L"+services+"TuneWeaveVideo;", null, null).visitEnd();
        mapped.visitField(Opcodes.ACC_PUBLIC, "parts", "Ljava/util/List;", null, null).visitEnd();
        mapped.visitField(Opcodes.ACC_PUBLIC, "tasks", "Lindi/mopelotus/musichud/client/ui/ScopedViewTasks;", null, null).visitEnd();
        var init = mapped.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD,0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL,source.superName,"<init>","()V",false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0,0); init.visitEnd();
        for (var method : source.methods) if (method.name.equals("play") || method.name.equals("playAll") || method.name.equals("message")) {
            method.access = (method.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC;
            method.accept(mapped);
        }
        mapped.visitEnd();
        class Loader extends ClassLoader {
            Loader() { super(VideoPartSubmissionTest.class.getClassLoader()); }
            Class<?> load() { byte[] bytes=writer.toByteArray();return defineClass(target.replace('/','.'),bytes,0,bytes.length); }
        }
        Class<?> type = new Loader().load(); Page page = (Page) type.getConstructor().newInstance();
        type.getField("tuneWeave").set(page,new Host()); type.getField("video").set(page,new Video());
        type.getField("tasks").set(page,new Tasks()); type.getField("parts").set(page,List.of());
        return page;
    }
}
