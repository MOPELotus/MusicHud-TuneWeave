package indi.mopelotus.musichud.client.audio;

import java.util.concurrent.ConcurrentHashMap;

/** Context and lease identity prevent late cleanup from claiming a reused OpenAL source ID. */
public final class OwnedAudioSources {
    public static final OwnedAudioSources INSTANCE = new OwnedAudioSources();
    private record Key(long context, int source) {}
    public static final class Lease {
        private final Key key;
        private Lease(Key key) { this.key = key; }
    }
    private final ConcurrentHashMap<Key, Lease> owners = new ConcurrentHashMap<>();

    public synchronized Lease register(long context, int source) {
        if (context == 0 || source <= 0) throw new IllegalArgumentException("Invalid OpenAL source identity");
        var lease = new Lease(new Key(context, source));
        owners.put(lease.key, lease);
        return lease;
    }

    public synchronized boolean owns(long context, int source) { return owners.containsKey(new Key(context, source)); }
    /** Retirement waits until the callback finishes; foreign/reused source IDs are never visited. */
    public synchronized void forEach(long context, java.util.function.IntConsumer action) {
        for (var key : owners.keySet()) if (key.context() == context) action.accept(key.source());
    }

    public synchronized void release(Lease lease) { if (lease != null) owners.remove(lease.key, lease); }
}
