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

    @Test void manualServiceCheckReplacesProgressWithTheActualOffer() throws Exception {
        try (var e = new Environment(temp)) {
            e.attach(); e.drain(); e.click(e.last(), 0); e.drain();
            e.loadClass(UI).getMethod("manualServiceCheck").invoke(null); e.drain();
            assertTrue(e.texts(e.last()).contains("update.checking"));
            e.serviceState("AVAILABLE"); e.drain();
            assertTrue(e.texts(e.last()).contains("update.service.available"));
            assertFalse(e.texts(e.last()).contains("update.service.failed"));
            e.click(e.last(), 0); e.drain();
            assertTrue(e.texts(e.last()).contains("update.service.downloading"));
            e.serviceState("READY"); e.drain();
            assertTrue(e.texts(e.last()).contains("update.service.ready"));
            int shown = e.modals().size(); e.dismiss(e.last()); e.drain();
            assertEquals(shown, e.modals().size());
        }
    }

    @Test void lateServiceResultCannotOpenAClosedFragment() throws Exception {
        try (var e = new Environment(temp)) {
            Object ui = e.attach(); e.drain(); e.click(e.last(), 0); e.drain();
            int before = e.modals().size();
            e.serviceState("AVAILABLE"); e.close(ui); e.drain();
            assertEquals(before, e.modals().size());
        }
    }

    @Test void externalAndUnknownServiceChecksNeverPresentAnInstallAction() throws Exception {
        try (var e = new Environment(temp)) {
            e.attach(); e.drain(); e.click(e.last(), 0); e.drain();
            for (String state : List.of("EXTERNAL", "UNKNOWN", "INCOMPATIBLE")) {
                e.loadClass(UI).getMethod("manualServiceCheck").invoke(null); e.drain();
                e.serviceState(state); e.drain();
                assertTrue(e.texts(e.last()).contains("update.service." + state.toLowerCase(java.util.Locale.ROOT)));
                assertFalse(e.texts(e.last()).contains("update.service.available"));
                e.dismiss(e.last()); e.drain();
            }
        }
    }

    @Test void hiddenServiceProgressStaysHiddenThroughParentRendersAndShowsOneTerminalResult() throws Exception {
        try (var e = new Environment(temp)) {
            e.enableModUpdates(); Object ui = e.attach(); e.drain(); e.click(e.last(), 0); e.drain();
            e.modState("CURRENT"); e.drain();
            e.loadClass(UI).getMethod("manualServiceCheck").invoke(null); e.drain();
            assertTrue(e.texts(e.last()).contains("update.checking"));
            int shown = e.modals().size(); e.click(e.last(), 0); e.drain();
            assertEquals(shown, e.modals().size());
            e.modState("CURRENT"); e.drain(); assertEquals(shown, e.modals().size());
            e.serviceState("AVAILABLE"); e.drain(); e.click(e.last(), 0); e.drain();
            assertTrue(e.texts(e.last()).contains("update.service.downloading"));
            shown = e.modals().size(); e.click(e.last(), 0); e.drain();
            e.modState("CURRENT"); e.drain(); assertEquals(shown, e.modals().size());
            e.serviceState("READY"); e.drain(); assertEquals(shown + 1, e.modals().size());
            assertTrue(e.texts(e.last()).contains("update.service.ready"));
            e.click(e.last(), 0); e.drain(); e.modState("CURRENT"); e.drain();
            assertEquals(shown + 1, e.modals().size()); e.close(ui);
        }
    }

    @Test void explicitServiceRecheckCanReopenHiddenProgressButDetachRejectsLateResults() throws Exception {
        try (var e = new Environment(temp)) {
            e.enableModUpdates(); Object ui = e.attach(); e.drain(); e.click(e.last(), 0); e.drain();
            e.modState("CURRENT"); e.drain();
            e.loadClass(UI).getMethod("manualServiceCheck").invoke(null); e.drain();
            e.click(e.last(), 0); e.drain(); int shown = e.modals().size();
            e.loadClass(UI).getMethod("manualServiceCheck").invoke(null); e.drain();
            assertEquals(shown + 1, e.modals().size());
            assertTrue(e.texts(e.last()).contains("update.checking"));
            e.close(ui); e.serviceState("AVAILABLE"); e.modState("AVAILABLE"); e.drain();
            assertEquals(shown + 1, e.modals().size());
        }
    }

    @Test void modOfferKeepsPriorityAndSuppressedModRenderCannotReopenHiddenServiceDownload() throws Exception {
        try (var e = new Environment(temp)) {
            e.enableModUpdates(); e.attach(); e.drain(); e.click(e.last(), 0); e.drain();
            e.modState("AVAILABLE"); e.drain(); int shown = e.modals().size();
            assertTrue(e.texts(e.last()).contains("musichud_tuneweave.update.available"));
            e.serviceState("AVAILABLE"); e.drain(); assertEquals(shown, e.modals().size());
            e.click(e.last(), 1); e.drain(); assertEquals(shown + 1, e.modals().size());
            assertTrue(e.texts(e.last()).contains("update.service.available"));
            e.click(e.last(), 0); e.drain();
            assertTrue(e.texts(e.last()).contains("update.service.downloading"));
            shown = e.modals().size(); e.click(e.last(), 0); e.drain();
            e.modState("AVAILABLE"); e.drain(); assertEquals(shown, e.modals().size());
        }
    }

    @Test void serviceRecheckDuringDismissAnimationCreatesOnlyOneNewProgressDialog() throws Exception {
        try (var e = new Environment(temp)) {
            e.enableModUpdates(); e.attach(); e.drain(); e.click(e.last(), 0); e.drain();
            e.modState("CURRENT"); e.drain();
            e.loadClass(UI).getMethod("manualServiceCheck").invoke(null); e.drain();
            int shown = e.modals().size();
            e.click(e.last(), 0); // The real dismissal callback is queued and has not run yet.
            e.loadClass(UI).getMethod("manualServiceCheck").invoke(null);
            e.drain();
            assertEquals(shown + 1, e.modals().size());
            int visible = 0;
            for (Object dialog : e.modals()) if ((boolean) dialog.getClass().getMethod("isShowing").invoke(dialog)) visible++;
            assertEquals(1, visible);
            assertTrue(e.texts(e.last()).contains("update.checking"));
            e.click(e.last(), 0); e.drain();
            assertEquals(shown + 1, e.modals().size());
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
                        || name.startsWith(ROOT + "client.update.ServiceUpdateUi") || name.startsWith(ROOT + "client.update.DownloadSourceUi")
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
        void enableModUpdates() throws Exception { loadClass(ROOT + "client.update.ClientUpdateService").getMethod("enable").invoke(null); }
        @SuppressWarnings({"rawtypes", "unchecked"}) void modState(String name) throws Exception {
            Class status = loadClass(ROOT + "client.update.ClientUpdateService$Status");
            Class<?> type = loadClass(ROOT + "client.update.ClientUpdateService");
            type.getMethod("publish", status).invoke(type.getField("INSTANCE").get(null), Enum.valueOf(status, name));
        }
        @SuppressWarnings({"rawtypes", "unchecked"}) void serviceState(String name) throws Exception {
            Class state = loadClass(ROOT + "client.update.ServiceUpdateService$Status");
            Object service = loadClass(ROOT + "client.update.ServiceUpdateService").getMethod("getInstance").invoke(null);
            service.getClass().getMethod("publish", state).invoke(service, Enum.valueOf(state, name));
        }
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
            s.put(ROOT + "server.api.ApiServerFetcher", "public class ApiServerFetcher {public static final String TUNEWEAVE_MANIFEST_URL=\"https://example.invalid/manifest\";public enum DownloadProxy{AUTO,DIRECT,PROXY}public record ReleaseSummary(String tag,String title,String htmlUrl,String publishedAt){} }");
            s.put(API, """
                public class ApiBinaryUpdateService {
                    public record UpdateOffer(String currentVersion,String targetVersion){}
                    public static int downloads,installs; public static final java.util.concurrent.CompletableFuture<ApiServerFetcher.ReleaseSummary> manifest=new java.util.concurrent.CompletableFuture<>();
                    public static final java.util.concurrent.CompletableFuture<DownloadedRelease> download=new java.util.concurrent.CompletableFuture<>();
                    public static ApiBinaryUpdateService getInstance(){return new ApiBinaryUpdateService();}
                    public java.util.concurrent.CompletableFuture<ApiServerFetcher.ReleaseSummary> fetchLatestRelease(){return manifest;}
                    public java.util.concurrent.CompletableFuture<DownloadedRelease> downloadToTemp(java.nio.file.Path p,ApiServerFetcher.DownloadProxy proxy,java.util.function.BiConsumer<Long,Long> progress,java.util.concurrent.atomic.AtomicBoolean cancel){downloads++;return download;}
                    public record DownloadedRelease(String tag,String version,java.nio.file.Path tempFile){}
                    public String findInstalledVersion(java.nio.file.Path p,String tag){return null;}
                    public java.nio.file.Path resolveFinalPath(DownloadedRelease d){installs++;throw new AssertionError("automatic install");}
                    public boolean recordManagedInstallation(java.nio.file.Path p,String tag,String version,String file){return false;}
                    public boolean recordManagedInstallation(java.nio.file.Path p,DownloadedRelease release,String file){return false;}
                    public String relativizePath(java.nio.file.Path p){return p.toString();}
                }
                """);
            s.put(ROOT + "client.update.ClientUpdateCatalog", "public class ClientUpdateCatalog {public record Offer(String version){public java.net.URI releasePageUri(){return java.net.URI.create(\"https://example.invalid/release\");}} }");
            s.put(ROOT + "client.update.ClientUpdateService", """
                public class ClientUpdateService {
                    public static ClientUpdateService INSTANCE=null;
                    public static void enable(){INSTANCE=new ClientUpdateService();}
                    public enum Status {IDLE,CHECKING,CURRENT,AVAILABLE,DOWNLOADING,READY,FAILED}
                    public record State(Status status,ClientUpdateCatalog.Offer offer,String message){}
                    private State state=new State(Status.IDLE,null,"");
                    private final java.util.List<java.util.function.Consumer<State>> listeners=new java.util.ArrayList<>();
                    public AutoCloseable subscribe(java.util.function.Consumer<State> listener){listeners.add(listener);return ()->listeners.remove(listener);}
                    public State state(){return state;}
                    public void publish(Status status){state=new State(status,new ClientUpdateCatalog.Offer("1.4.0+26.3"),"");for(var listener:java.util.List.copyOf(listeners))listener.accept(state);}
                    public void check(){publish(Status.CHECKING);} public void download(){download("AUTO");} public void download(String source){publish(Status.DOWNLOADING);}
                }
                """);
            s.put(ROOT + "client.update.ServiceUpdateService", """
                public class ServiceUpdateService {
                    private static final ServiceUpdateService INSTANCE=new ServiceUpdateService();
                    public static ServiceUpdateService getInstance(){return INSTANCE;}
                    public enum Status {IDLE,CHECKING,CURRENT,AVAILABLE,EXTERNAL,UNKNOWN,INCOMPATIBLE,DOWNLOADING,READY,FAILED}
                    public record State(Status status,indi.mopelotus.musichud.server.api.ApiBinaryUpdateService.UpdateOffer offer,String message){}
                    private State state=new State(Status.IDLE,null,"");
                    private final java.util.List<java.util.function.Consumer<State>> listeners=new java.util.ArrayList<>();
                    public AutoCloseable subscribe(java.util.function.Consumer<State> listener){listeners.add(listener);return ()->listeners.remove(listener);}
                    public State state(){return state;}
                    public void publish(Status status){state=new State(status,new indi.mopelotus.musichud.server.api.ApiBinaryUpdateService.UpdateOffer("1.0.0","1.0.1"),"");for(var listener:java.util.List.copyOf(listeners))listener.accept(state);}
                    public void check(){publish(Status.CHECKING);}
                    public void download(String source){publish(Status.DOWNLOADING);}
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
