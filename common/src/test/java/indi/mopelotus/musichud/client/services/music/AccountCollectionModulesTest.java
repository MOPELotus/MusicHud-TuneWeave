package indi.mopelotus.musichud.client.services.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountCollectionModulesTest {
    @AfterEach void clear() { MusicEntityCache.clear(); }

    @Test void slowPlaylistsAndFailedAlbumsDoNotBlockArtistsAndRetryOnlyReloadsFailure() {
        var modules = new AccountCollectionModules<String, String, String>();
        List<Runnable> tasks = new ArrayList<>();
        var playlists = modules.playlists(false, () -> "playlists", tasks::add);
        var albums = modules.albums(false, () -> { throw new IllegalStateException("offline"); }, tasks::add);
        var artists = modules.artists(false, () -> "artists", tasks::add);
        tasks.get(2).run();
        tasks.get(1).run();
        assertEquals("artists", artists.join());
        assertFalse(playlists.isDone());
        assertThrows(CompletionException.class, albums::join);
        assertEquals("retry albums", modules.albums(false, () -> "retry albums", Runnable::run).join());
        assertEquals("artists", modules.artists(false, () -> fail("artists reloaded"), Runnable::run).join());
        tasks.getFirst().run();
        assertEquals("playlists", playlists.join());
    }

    @Test void subscriptionsSharePendingModuleAndRefreshDoesNotCancelOtherModules() {
        var modules = new AccountCollectionModules<String, String, String>();
        List<Runnable> tasks = new ArrayList<>();
        var first = modules.playlists(false, () -> "first", tasks::add);
        assertSame(first, modules.playlists(false, () -> fail("duplicate request"), tasks::add));
        var albums = modules.albums(false, () -> "albums", tasks::add);
        var refreshed = modules.playlists(true, () -> "new", tasks::add);
        tasks.forEach(Runnable::run);
        assertThrows(CompletionException.class, first::join);
        assertEquals("new", refreshed.join());
        assertEquals("albums", albums.join());
    }

    @Test void accountSwitchInvalidatesAllModulesAndRejectsLateCompletions() {
        var modules = new AccountCollectionModules<String, String, String>();
        List<Runnable> tasks = new ArrayList<>();
        var playlists = modules.playlists(false, () -> "old", tasks::add);
        var albums = modules.albums(false, () -> "old", tasks::add);
        var artists = modules.artists(false, () -> "old", tasks::add);
        modules.invalidate();
        assertEquals("new", modules.playlists(false, () -> "new", Runnable::run).join());
        tasks.forEach(Runnable::run);
        for (var future : List.of(playlists, albums, artists)) {
            assertInstanceOf(CancellationException.class,
                    assertThrows(CompletionException.class, future::join).getCause());
        }
    }
}
