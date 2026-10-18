package indi.mopelotus.musichud.client.update;


import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.server.api.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Standard-only managed service updates. A failed service update never changes the mod update result. */
public final class ServiceUpdateService {
    public enum Status { IDLE, CHECKING, CURRENT, AVAILABLE, EXTERNAL, UNKNOWN, INCOMPATIBLE, DOWNLOADING, READY, FAILED }
    public record State(Status status, ApiBinaryUpdateService.UpdateOffer offer, String message) {}
    interface Backend {
        State check() throws Exception;
        String install(ApiBinaryUpdateService.UpdateOffer offer, String source, AtomicBoolean cancelled) throws Exception;
    }
    private static ServiceUpdateService instance;
    public static synchronized ServiceUpdateService getInstance() {
        if (instance == null) instance = new ServiceUpdateService(MusicHud.EXECUTOR, new ManagedBackend());
        return instance;
    }
    private final Executor executor;
    private final Backend backend;
    private final Set<Consumer<State>> listeners = new HashSet<>();
    private State state = new State(Status.IDLE, null, "");
    private long generation;
    private AtomicBoolean cancelled = new AtomicBoolean();
    ServiceUpdateService(Executor executor, Backend backend) { this.executor = executor; this.backend = backend; }
    public synchronized State state() { return state; }
    public synchronized AutoCloseable subscribe(Consumer<State> listener) {
        listeners.add(listener);
        return () -> { synchronized (this) { listeners.remove(listener); } };
    }
    private synchronized void publish(State next) {
        state = next;
        for (var listener : List.copyOf(listeners)) listener.accept(next);
    }
    private synchronized void complete(long operation, State next) {
        if (operation == generation) publish(next);
    }
    public synchronized void check() {
        if (state.status() == Status.DOWNLOADING) return;
        cancelled.set(true); cancelled = new AtomicBoolean();
        long operation = ++generation;
        publish(new State(Status.CHECKING, null, ""));
        executor.execute(() -> {
            try { complete(operation, backend.check()); }
            catch (Exception failure) {
                complete(operation, new State(Status.FAILED, null, "check"));
                MusicHud.LOGGER.debug("Service update check failed ({})", failure.getClass().getSimpleName());
            }
        });
    }
    public synchronized void download(String source) {
        if (state.status() != Status.AVAILABLE && !(state.status() == Status.FAILED && state.offer() != null)) return;
        var offer = state.offer();
        var cancellation = cancelled = new AtomicBoolean();
        long operation = ++generation;
        publish(new State(Status.DOWNLOADING, offer, ""));
        executor.execute(() -> {
            try {
                String result = backend.install(offer, source, cancellation);
                complete(operation, new State(Status.READY, offer, result));
            } catch (Exception failure) {
                complete(operation, new State(Status.FAILED, offer, "download"));
                MusicHud.LOGGER.warn("Service update failed ({})", failure.getClass().getSimpleName());
            }
        });
    }
    private static final class ManagedBackend implements Backend {
        public State check() {
            var config = ServerConfig.getInstance();
            String binary = config.getServerApiBinaryExecutablePath();
            String url = ClientConfig.getInstance().getTuneWeaveBaseUrl();
            if (!ApiBinaryUpdateService.usesManagedEndpoint(config, url)) return new State(Status.EXTERNAL, null, "");
            var manager = ApiServerManager.getInstance();
            if (manager == null) return new State(Status.EXTERNAL, null, "");
            var before = manager.managedProcessSnapshot();
            if (before.status() != ApiServerManager.BinaryApiServerStatus.STOPPED && !before.owned())
                return new State(Status.EXTERNAL, null, "");
            if (before.status() == ApiServerManager.BinaryApiServerStatus.STOPPED && !ServiceUpdateHealth.localPortAvailableForStart(url))
                return new State(Status.EXTERNAL, null, "portUnavailable");
            var checked = ApiBinaryUpdateService.getInstance().checkManagedUpdate(binary, url).join();
            if (!Objects.equals(binary, config.getServerApiBinaryExecutablePath())
                    || !Objects.equals(url, ClientConfig.getInstance().getTuneWeaveBaseUrl()))
                throw new IllegalStateException("Service selection changed during update check");
            if (checked.status() == ApiBinaryUpdateService.CheckStatus.UNMANAGED)
                return new State(Status.EXTERNAL, null, "");
            var offer = checked.offer();
            if (checked.status() == ApiBinaryUpdateService.CheckStatus.UNKNOWN) return new State(Status.UNKNOWN, offer, "");
            if (checked.status() == ApiBinaryUpdateService.CheckStatus.CURRENT) return new State(Status.CURRENT, offer, "");
            // Newer does not imply compatible: installation also requires the publisher profile.
            Status compatible = ServiceUpdateCompatibility.evaluate(offer);
            if (compatible != Status.AVAILABLE) return new State(compatible, offer, "");
            return new State(Status.AVAILABLE, offer, "");
        }
        public String install(ApiBinaryUpdateService.UpdateOffer offer, String source, AtomicBoolean cancelled) throws Exception {
            if (ServiceUpdateCompatibility.evaluate(offer) != Status.AVAILABLE) throw new IllegalStateException("Service compatibility changed");
            var service = ApiBinaryUpdateService.getInstance();
            var staged = service.stageUpdate(offer, ApiServerFetcher.DownloadProxy.valueOf(source), (done, total) -> {}, cancelled).join();
            if (cancelled.get()) throw new java.util.concurrent.CancellationException();
            var result = service.activateAndRestartVerified(staged, ServerConfig.getInstance(),
                    () -> ClientConfig.getInstance().getTuneWeaveBaseUrl()).join();
            return switch (result) {
                case ACTIVE -> "";
                case NEXT_START -> "nextStart";
                default -> throw new IllegalStateException("Service activation was not confirmed: " + result);
            };
        }
    }
}
