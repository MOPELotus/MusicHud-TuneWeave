package indi.mopelotus.musichud.client.update;

import com.google.gson.*;
import indi.mopelotus.musichud.BuildDistribution;
import indi.mopelotus.musichud.MusicHud;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** One update operation per client. All callbacks carry immutable state and a subscription identity. */
public final class ClientUpdateService {
    public enum Status { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, FAILED }
    public record State(Status status, ClientUpdateCatalog.Offer offer, String message) {}
    interface Backend {
        Optional<ClientUpdateCatalog.Offer> check() throws Exception;
        void stage(ClientUpdateCatalog.Offer offer) throws Exception;
        default void cleanSuccessfulUpdate() throws Exception {}
    }
    public static volatile ClientUpdateService INSTANCE;
    private final Executor executor;
    private final Backend backend;
    private final Set<Consumer<State>> listeners = new HashSet<>();
    private boolean startupConfirmed;
    private State state = new State(Status.IDLE, null, "");
    ClientUpdateService(Executor executor, Backend backend) { this.executor = executor; this.backend = backend; }
    public synchronized State state() { return state; }
    public synchronized AutoCloseable subscribe(Consumer<State> listener) {
        listeners.add(listener);
        return () -> { synchronized (this) { listeners.remove(listener); } };
    }
    private synchronized void set(State next) {
        state = next;
        for (var listener : List.copyOf(listeners)) listener.accept(next);
    }
    public synchronized void check() {
        if (Set.of(Status.CHECKING, Status.DOWNLOADING, Status.READY).contains(state.status())) return;
        set(new State(Status.CHECKING, null, ""));
        executor.execute(() -> {
            try { var found = backend.check(); set(new State(found.isPresent() ? Status.AVAILABLE : Status.CURRENT, found.orElse(null), "")); }
            catch (Exception e) { set(new State(Status.FAILED, null, "check")); MusicHud.LOGGER.debug("Mod update check failed ({})", e.getClass().getSimpleName()); }
        });
    }
    public synchronized void download() {
        if (!UpdateInstallation.enabled() || state.status() != Status.AVAILABLE) return;
        var offer = state.offer(); set(new State(Status.DOWNLOADING, offer, ""));
        executor.execute(() -> {
            try { backend.stage(offer); set(new State(Status.READY, offer, "")); }
            catch (Exception e) { set(new State(Status.FAILED, null, "download")); MusicHud.LOGGER.warn("Mod update staging failed ({})", e.getClass().getSimpleName()); }
        });
    }
    public synchronized void clientStartedSuccessfully() {
        if (!UpdateInstallation.enabled() || startupConfirmed) return;
        startupConfirmed = true;
        executor.execute(() -> {
            try { backend.cleanSuccessfulUpdate(); }
            catch (Exception e) { MusicHud.LOGGER.debug("Update backup cleanup deferred ({})", e.getClass().getSimpleName()); }
        });
    }
    public static void watchSuccessfulStartup() {
        if (!UpdateInstallation.enabled()) return;
        var registration = new indi.mopelotus.musichud.interfaces.Unregister[1];
        int[] ticks = {0};
        registration[0] = indi.mopelotus.musichud.client.interfaces.IClientEventService.getInstance().registerClientTickPost(() -> {
            var client = net.minecraft.client.Minecraft.getInstance();
            boolean ready = client.level != null || client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen
                    || client.gui.screen() instanceof indi.mopelotus.musichud.client.ui.screen.MusicHudScreen;
            if (!ready || client.gui.overlay() != null) { ticks[0] = 0; return; }
            if (++ticks[0] < 20) return;
            if (registration[0] != null) registration[0].unregister();
            var service = INSTANCE;
            if (service != null) service.clientStartedSuccessfully();
        });
    }
    public static void initialize(Path jar, String version, String loader, String minecraft) {
        try {
            var installed = new ClientUpdateCatalog.Installed(version, BuildDistribution.EDITION, loader, minecraft);
            INSTANCE = new ClientUpdateService(MusicHud.EXECUTOR, new NetworkBackend(jar, installed));
            INSTANCE.check();
        } catch (RuntimeException e) { MusicHud.LOGGER.warn("Mod updater unavailable for this development installation"); }
    }
    private record NetworkBackend(Path jar, ClientUpdateCatalog.Installed installed) implements Backend {
        public synchronized void cleanSuccessfulUpdate() throws Exception {
            UpdateInstallation.clean(jar, installed);
        }
        public Optional<ClientUpdateCatalog.Offer> check() throws Exception {
            JsonArray releases = JsonParser.parseString(text(URI.create("https://api.github.com/repos/" + ClientUpdateCatalog.REPOSITORY + "/releases?per_page=20"))).getAsJsonArray();
            ClientUpdateCatalog.Offer best = null;
            for (JsonElement element : releases) {
                var release = element.getAsJsonObject();
                if (release.get("draft").getAsBoolean()) continue;
                String tag = ClientUpdateCatalog.string(release, "tag_name");
                try { ClientUpdateCatalog.assetUri(tag, "catalog.json"); } catch (IllegalArgumentException ignored) { continue; }
                ReleaseVersion target = ReleaseVersion.parse(tag.substring(1)), current = ReleaseVersion.parse(installed.version());
                // Equal base versions can still contain a newer CF packaging revision.
                if (target.compareTo(new ReleaseVersion(current.major(), current.minor(), current.patch(), current.pre(), 0)) < 0) continue;
                String catalogName = "client-updates-" + installed.distribution() + ".json";
                boolean published = false;
                for (JsonElement asset : release.getAsJsonArray("assets")) if (catalogName.equals(ClientUpdateCatalog.string(asset.getAsJsonObject(), "name"))) published = true;
                if (!published) continue;
                var offer = ClientUpdateCatalog.select(text(ClientUpdateCatalog.assetUri(tag, catalogName)), tag, installed, true,
                        release.has("body") && !release.get("body").isJsonNull() ? release.get("body").getAsString() : "");
                if (offer.isPresent() && (best == null || ReleaseVersion.parse(offer.get().version()).compareTo(ReleaseVersion.parse(best.version())) > 0)) best = offer.get();
            }
            return Optional.ofNullable(best);
        }
        public synchronized void stage(ClientUpdateCatalog.Offer offer) throws Exception {
            UpdateInstallation.stage(jar, installed, offer);
        }
    }
    static String text(URI uri) throws IOException {
        var out = new ByteArrayOutputStream(); transfer(uri, out, 1_048_576); return out.toString(StandardCharsets.UTF_8);
    }
    static void transfer(URI uri, OutputStream out, long limit) throws IOException {
        for (int redirects = 0; redirects < 6; redirects++) {
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || !Set.of("github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com").contains(host)) throw new IOException("Untrusted update host");
            var connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "MusicHud-TuneWeave-Updater");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status <= 399) { uri = uri.resolve(connection.getHeaderField("Location")); continue; }
                if (status != 200 || connection.getContentLengthLong() > limit) throw new IOException("Update HTTP response rejected");
                try (var in = connection.getInputStream()) {
                    byte[] bytes = new byte[65536]; long total = 0; int n;
                    while ((n = in.read(bytes)) != -1) { total += n; if (total > limit) throw new IOException("Oversized update response"); out.write(bytes, 0, n); }
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many update redirects");
    }
}
