package indi.mopelotus.musichud.client.network.vanilla;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.concurrent.TimeUnit;

/** Keeps proxies scoped to actual player objects, even when entity IDs are reused. */
final class PlayerProxyCache {
    private PlayerProxyCache() {}

    static <P, V> Cache<P, V> create() {
        // Entity.equals/hashCode use its numeric ID, which is not unique across worlds
        // or respawns. Guava weak keys compare object identity, and Cache.get retains
        // its atomic single-loader behavior for concurrent requests for one player.
        return CacheBuilder.newBuilder()
                .weakKeys()
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .build();
    }
}
