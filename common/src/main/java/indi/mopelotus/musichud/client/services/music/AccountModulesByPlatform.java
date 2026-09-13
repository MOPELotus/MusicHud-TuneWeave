package indi.mopelotus.musichud.client.services.music;

import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import java.util.EnumMap;
import java.util.Objects;

final class AccountModulesByPlatform<P, A, R> {
    private final EnumMap<TuneWeavePlatform, AccountCollectionModules<P, A, R>> modules = new EnumMap<>(TuneWeavePlatform.class);
    synchronized AccountCollectionModules<P, A, R> forPlatform(TuneWeavePlatform platform) {
        return modules.computeIfAbsent(Objects.requireNonNull(platform), ignored -> new AccountCollectionModules<>());
    }
    synchronized void invalidate() { MusicEntityCache.clear(); modules.values().forEach(AccountCollectionModules::invalidate); modules.clear(); }
}
