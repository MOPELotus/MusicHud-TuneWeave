package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;

public class PlaylistSubscribeState extends SubscribeState<Playlist> {
    private static final MusicService musicService = MusicService.getInstance();
    private static final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();

    public PlaylistSubscribeState(long id) {
        super(id, Playlist.class,
                (id1) -> musicService.loadPlaylistDetail(id1, false),
                () -> musicService.loadAccountPlaylists(tuneWeave.platformOfEntity(Playlist.class, id), false, ignored -> {})
                        .thenApply(indi.mopelotus.musichud.beans.music.UserCategoryPlaylists::getSubscribedPlaylist),
                () -> tuneWeave.prepareBiConsumer(tuneWeave::setPlaylistSubscribed)
        );
    }
    @Override public java.util.concurrent.CompletableFuture<Boolean> isSupported() {
        var request = tuneWeave.prepareRequest(() -> tuneWeave.capabilities(tuneWeave.platformOfEntity(Playlist.class, getBeanId())).contains("playlist_subscription_write"));
        return java.util.concurrent.CompletableFuture.supplyAsync(request, indi.mopelotus.musichud.MusicHud.EXECUTOR);
    }
}
