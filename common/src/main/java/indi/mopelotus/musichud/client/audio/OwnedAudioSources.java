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

    public Lease register(long context, int source) {
        if (context == 0 || source <= 0) throw new IllegalArgumentException("Invalid OpenAL source identity");
        var lease = new Lease(new Key(context, source));
        owners.put(lease.key, lease);
        return lease;
    }

    public boolean owns(long context, int source) { return owners.containsKey(new Key(context, source)); }
    public void release(Lease lease) { if (lease != null) owners.remove(lease.key, lease); }
}
