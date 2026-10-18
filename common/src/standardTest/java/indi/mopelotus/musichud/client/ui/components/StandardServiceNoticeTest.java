package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.View;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

/** Real notice, download controls and shared session bytecode; only rendering/scheduling/network endpoints are fixtures. */
class StandardServiceNoticeTest {
    @TempDir Path temp;
    static final String ROOT = "indi.mopelotus.musichud.";
    static final String UI = ROOT + "client.update.ClientUpdateUi";
    static final String MODAL = ROOT + "client.ui.components.Modal";
    static final String API = ROOT + "server.api.ApiBinaryUpdateService";
    static final String SESSION = ROOT + "client.ui.pages.ApiDownloadSession";

    @Test void acknowledgementIsPersistedSeparatelyFromCfAndSuppressesOnlySubsequentNotices() throws Exception {
        try (var e = new Environment(temp)) {
            Path cf = temp.resolve("musichud-tuneweave/cf-notice-v2.accepted");
            Files.createDirectories(cf.getParent()); Files.writeString(cf, "cf\n");
            Object ui = e.attach(); e.drain();
            assertEquals(1, e.modals().size()); e.click(e.last(), 0); e.drain();
            assertEquals("1\n", Files.readString(e.notice())); assertEquals("cf\n", Files.readString(cf));
            e.close(ui); e.attach(); e.drain();
            assertEquals(1, e.modals().size()); assertEquals(0, e.downloads());
        }
    }

    @Test void goDownloadOpensRealControlsAndStartsOnceWhileManifestIsStillPending() throws Exception {
        try (var e = new Environment(temp)) {
            Object ui = e.attach(); e.drain(); e.click(e.last(), 1); e.drain();
            assertEquals(2, e.modals().size()); assertEquals(1, e.downloads());
            assertEquals("DOWNLOADING", e.page());
            assertFalse(e.future("manifest").isDone()); assertFalse(e.future("download").isDone());
            assertEquals(0, e.installs()); assertTrue(Files.isRegularFile(e.notice()));
            Object download = e.last();
            e.openDownload(); e.drain();
            assertEquals(1, e.downloads()); // Reopening a running session is display-only.
            e.close(ui); e.dismiss(download); e.drain();
        }
    }

    @Test void theFirstExplicitChoiceWinsDuringDismissAnimation() throws Exception {
        try (var e = new Environment(temp)) {
            e.attach(); e.drain(); Object notice = e.last();
            e.click(notice, 0); e.click(notice, 1); e.drain();
            assertEquals(1, e.modals().size()); assertEquals(0, e.downloads());
            assertTrue(Files.isRegularFile(e.notice()));
        }
    }

    @Test void failedManifestOrDownloadReturnsToIdleWithoutAutomaticRetries() throws Exception {
        try (var e = new Environment(temp)) {
            e.attach(); e.drain(); Object notice=e.last();
            e.click(notice, 1); e.click(notice, 1); e.drain();
            assertEquals(1, e.downloads());
            e.future("download").completeExceptionally(new IOException("manifest unavailable")); e.drain();
            assertEquals("IDLE", e.page()); assertEquals(1, e.downloads()); assertEquals(0, e.installs());
        }
    }

    @Test void aCompletedDownloadIsDisplayedWithoutInstallingOrStartingIt() throws Exception {
        try (var e = new Environment(temp)) {
            e.startSession(); e.complete(); e.openDownload(); e.drain();
            assertEquals("DONE", e.page()); assertEquals(0, e.downloads()); assertEquals(0, e.installs());
            e.dismiss(e.last());
        }
    }

    @Test void closingBeforeQueuedAttachOrDismissCallbacksCannotOpenTheDownload() throws Exception {
        try (var e = new Environment(temp)) {
            Object ui = e.attach(); e.close(ui); e.drain(); assertTrue(e.modals().isEmpty());
            ui = e.attach(); e.drain(); e.click(e.last(), 1); // dismiss completion is queued like the real animation
            e.close(ui); e.drain();
            assertEquals(1, e.modals().size()); assertEquals(0, e.downloads());
        }
    }

    @Test void lateManifestAndDownloadCallbacksCannotMutateOrReopenClosedViews() throws Exception {
        try (var e = new Environment(temp)) {
            Object ui = e.attach(); e.drain(); e.click(e.last(), 1); e.drain();
            Object dialog = e.last(); String before = e.texts(dialog);
            e.close(ui); e.drain();
            e.future("manifest").complete(e.releaseSummary());
            e.future("download").complete(e.downloaded()); e.drain();
            assertEquals(before, e.texts(dialog)); assertEquals(2, e.modals().size());
            assertEquals("DONE", e.page()); assertEquals(0, e.installs());
        }
    }

    static final class Environment extends URLClassLoader {
        private final Path directory;
        private final List<View> owners = new ArrayList<>();
        private final Context context = new LayoutTestContext();
        Environment(Path directory) throws Exception {
            super(new java.net.URL[]{directory.toUri().toURL()}, StandardServiceNoticeTest.class.getClassLoader());
            this.directory = directory; compile(directory);
            loadClass(ROOT + "MusicHud").getField("config").set(null, directory);
            Files.createDirectories(directory.resolve("install"));
            loadClass(ROOT + "interfaces.ServerConfig$Impl").getField("path").set(null, directory.resolve("install/service").toString());
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                if (name.equals(UI) || name.startsWith(UI + "$") || name.equals(SESSION) || name.startsWith(SESSION + "$")
                        || name.equals(ROOT + "client.ui.pages.ApiDistributionUi") || name.startsWith(ROOT + "client.ui.pages.ApiDistributionUi$")) {
                    try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (in == null) throw new ClassNotFoundException(name);
                        byte[] bytes = in.readAllBytes(); type = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException error) { throw new ClassNotFoundException(name, error); }
                } else { try { type = findClass(name); } catch (ClassNotFoundException parent) { type = super.loadClass(name, false); } }
            }
            if (resolve) resolveClass(type); return type;
        }
        Object attach() throws Exception { return loadClass(UI).getMethod("attach", View.class).invoke(null, owner()); }
        View owner() { View view = new icyllis.modernui.widget.FrameLayout(context); owners.add(view); return view; }
        void close(Object ui) throws Exception { ((AutoCloseable) ui).close(); }
        void drain() throws Exception {
            var field = View.class.getDeclaredField("mRunQueue"); field.setAccessible(true);
            for (int turn=0;turn<100;turn++) {
                List<Runnable> posted = new ArrayList<>();
                for (View owner:owners) {
                    var queue=(icyllis.modernui.view.HandlerActionQueue) field.get(owner);
                    if(queue!=null) {for(int i=0;i<queue.size();i++)posted.add(queue.getRunnable(i));for(Runnable task:posted)queue.removeCallbacks(task);}
                }
                posted.forEach(Runnable::run);
                var work=(Collection<?>)loadClass(ROOT+"test.Scheduler").getField("work").get(null);
                if(posted.isEmpty()&&work.isEmpty())return;
                loadClass(ROOT+"test.Scheduler").getMethod("drain").invoke(null);
            }
            fail("Unbounded UI callbacks");
        }
        @SuppressWarnings("unchecked") List<Object> modals() throws Exception { return (List<Object>) loadClass(MODAL).getField("all").get(null); }
        Object last() throws Exception { return modals().getLast(); }
        void click(Object modal, int button) throws Exception { modal.getClass().getMethod("click", int.class).invoke(modal, button); }
        void dismiss(Object modal) throws Exception { modal.getClass().getMethod("dismiss").invoke(modal); }
        void openDownload() throws Exception { loadClass(ROOT + "client.ui.pages.ApiDistributionUi").getMethod("openAndStart", View.class, Runnable.class).invoke(null, owner(), (Runnable) () -> {}); }
        int downloads() throws Exception { return loadClass(API).getField("downloads").getInt(null); }
        int installs() throws Exception { return loadClass(API).getField("installs").getInt(null); }
        Object session() throws Exception { return loadClass(SESSION).getMethod("getInstance").invoke(null); }
        String page() throws Exception { Object snapshot = loadClass(SESSION).getMethod("snapshot").invoke(session()); return snapshot.getClass().getMethod("page").invoke(snapshot).toString(); }
        void startSession() throws Exception { loadClass(SESSION).getMethod("tryStart", Path.class).invoke(session(), directory.resolve("install")); }
        void complete() throws Exception { loadClass(SESSION).getMethod("complete", loadClass(API + "$DownloadedRelease")).invoke(session(), downloaded()); }
        Object downloaded() throws Exception { return loadClass(API + "$DownloadedRelease").getConstructor(String.class, String.class, Path.class).newInstance("v1", "1", directory.resolve("install/download.temp")); }
        Object releaseSummary() throws Exception { return loadClass(ROOT + "server.api.ApiServerFetcher$ReleaseSummary").getConstructor(String.class, String.class, String.class, String.class).newInstance("v1", "TuneWeave", "https://example.invalid", null); }
        @SuppressWarnings("unchecked") CompletableFuture<Object> future(String name) throws Exception { return (CompletableFuture<Object>) loadClass(API).getField(name).get(null); }
        Path notice() { return directory.resolve("musichud-tuneweave/standard-service-notice-v1.accepted"); }
        String texts(Object modal) throws Exception { return modal.getClass().getMethod("texts").invoke(modal).toString(); }

        static void compile(Path directory) throws Exception {
            Map<String,String> s = new LinkedHashMap<>();
            s.put(ROOT + "test.Scheduler", "public class Scheduler { public static final java.util.ArrayDeque<Runnable> work=new java.util.ArrayDeque<>(); public static void drain(){int n=0;while(!work.isEmpty()){if(++n>1000)throw new AssertionError(\"unbounded UI work\");work.removeFirst().run();}} }");
            s.put("icyllis.modernui.mc.MuiModApi", "public class MuiModApi {public static void postToUiThread(Runnable r){indi.mopelotus.musichud.test.Scheduler.work.add(r);} }");
            s.put(ROOT + "MusicHud", "public class MusicHud { public static final String MOD_ID=\"musichud_tuneweave\";public static java.nio.file.Path config;public static java.nio.file.Path getConfigDirectory(){return config;}public static final org.apache.logging.log4j.Logger LOGGER=org.apache.logging.log4j.LogManager.getLogger(); }");
            s.put("net.minecraft.client.resources.language.I18n", "public class I18n {public static String get(String key,Object...args){return key.endsWith(\".description.url\")?\"{url}\":key;} }");
            s.put(ROOT + "client.ui.ToastUtil", "public class ToastUtil {public static void show(CharSequence s){} }");
            s.put(ROOT + "client.utils.ui.InsetBackgroundFactory", "public class InsetBackgroundFactory {public void applyBackgroundTo(icyllis.modernui.view.View v){} public static InsetBackgroundFactoryBuilder builder(){return new InsetBackgroundFactoryBuilder();}public static class InsetBackgroundFactoryBuilder {public InsetBackgroundFactory build(){return new InsetBackgroundFactory();}} }");
            s.put(ROOT + "interfaces.ServerConfig", "public interface ServerConfig {static ServerConfig getInstance(){return new Impl();}String getServerApiBinaryExecutablePath();void setServerApiBinaryExecutablePath(String s);void save();class Impl implements ServerConfig {public static String path;public String getServerApiBinaryExecutablePath(){return path;}public void setServerApiBinaryExecutablePath(String s){path=s;}public void save(){}} }");
            s.put(ROOT + "server.api.ApiServerManager", "public class ApiServerManager {public static ApiServerManager getInstance(){return null;}public void restartApiServer(){throw new AssertionError(\"external process started\");} }");
            s.put(ROOT + "server.api.ApiServerFetcher", "public class ApiServerFetcher {public static final String TUNEWEAVE_MANIFEST_URL=\"https://example.invalid/manifest\";public enum DownloadProxy{DIRECT,PROXY}public record ReleaseSummary(String tag,String title,String htmlUrl,String publishedAt){} }");
            s.put(API, """
                public class ApiBinaryUpdateService {
                    public static int downloads,installs; public static final java.util.concurrent.CompletableFuture<ApiServerFetcher.ReleaseSummary> manifest=new java.util.concurrent.CompletableFuture<>();
                    public static final java.util.concurrent.CompletableFuture<DownloadedRelease> download=new java.util.concurrent.CompletableFuture<>();
                    public static ApiBinaryUpdateService getInstance(){return new ApiBinaryUpdateService();}
                    public java.util.concurrent.CompletableFuture<ApiServerFetcher.ReleaseSummary> fetchLatestRelease(){return manifest;}
                    public java.util.concurrent.CompletableFuture<DownloadedRelease> downloadToTemp(java.nio.file.Path p,ApiServerFetcher.DownloadProxy proxy,java.util.function.BiConsumer<Long,Long> progress,java.util.concurrent.atomic.AtomicBoolean cancel){downloads++;return download;}
                    public record DownloadedRelease(String tag,String version,java.nio.file.Path tempFile){}
                    public String findInstalledVersion(java.nio.file.Path p,String tag){return null;}
                    public java.nio.file.Path resolveFinalPath(DownloadedRelease d){installs++;throw new AssertionError("automatic install");}
                    public boolean recordManagedInstallation(java.nio.file.Path p,String tag,String version,String file){return false;}
                    public String relativizePath(java.nio.file.Path p){return p.toString();}
                }
                """);
            s.put(ROOT + "client.update.ClientUpdateService", """
                public class ClientUpdateService {
                    public static final ClientUpdateService INSTANCE=null;
                    public enum Status {IDLE,CHECKING,CURRENT,AVAILABLE,DOWNLOADING,READY,FAILED}
                    public record Offer(String version){} public record State(Status status,Offer offer,String message){}
                    public AutoCloseable subscribe(java.util.function.Consumer<State> s){return ()->{};}
                    public State state(){return new State(Status.CURRENT,null,"");} public void check(){} public void download(){}
                }
                """);
            s.put(MODAL, """
                public class Modal {
                    public static final java.util.List<Modal> all=new java.util.ArrayList<>();
                    final icyllis.modernui.widget.TextView title;final icyllis.modernui.view.View content;final ActionButton[] buttons; boolean showing;
                    icyllis.modernui.widget.PopupWindow.OnDismissListener listener;
                    public Modal(icyllis.modernui.core.Context c,icyllis.modernui.view.View v,ActionButton...b){this(c,null,v,b);}
                    public Modal(icyllis.modernui.core.Context c,icyllis.modernui.widget.TextView t,icyllis.modernui.view.View v,ActionButton...b){title=t;content=v;buttons=b;for(var a:b)a.button=new icyllis.modernui.widget.Button(c);}
                    public void show(){showing=true;all.add(this);} public boolean isShowing(){return showing;}
                    public void setOnDismissListener(icyllis.modernui.widget.PopupWindow.OnDismissListener l){listener=l;}
                    public void dismiss(){if(!showing)return;showing=false;var captured=listener;if(captured!=null)indi.mopelotus.musichud.test.Scheduler.work.add(captured::onDismiss);}
                    public void click(int i){buttons[i].action.accept(buttons[i],this);}
                    public String texts(){return text(title)+text(content);} private String text(icyllis.modernui.view.View v){String s=v instanceof icyllis.modernui.widget.TextView t?t.getText().toString():"";if(v instanceof icyllis.modernui.view.ViewGroup g)for(int i=0;i<g.getChildCount();i++)s+=text(g.getChildAt(i));return s;}
                    public static class ActionButton {String text;final java.util.function.BiConsumer<ActionButton,Modal> action;icyllis.modernui.widget.Button button;
                        public ActionButton(String t,java.util.function.BiConsumer<ActionButton,Modal>a){text=t;action=a;}
                        public java.util.function.BiConsumer<ActionButton,Modal> getOnClickListener(){return action;}
                        public void setText(String t){text=t;}public void setEnabled(boolean b){}public icyllis.modernui.widget.Button getButton(){return button;}
                    }
                }
                """);
            String cp=System.getProperty("java.class.path");
            for(Class<?> c:List.of(Context.class, org.apache.logging.log4j.Logger.class)) cp+=File.pathSeparator+Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI());
            var args=new ArrayList<>(List.of("-proc:none","-classpath",cp,"-d",directory.toString()));
            for(var entry:s.entrySet()){Path file=directory.resolve(entry.getKey().replace('.','/')+".java");Files.createDirectories(file.getParent());Files.writeString(file,"package "+entry.getKey().substring(0,entry.getKey().lastIndexOf('.'))+";\n"+entry.getValue());args.add(file.toString());}
            var errors=new ByteArrayOutputStream();assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,errors,errors,args.toArray(String[]::new)),errors::toString);
        }
    }
}
