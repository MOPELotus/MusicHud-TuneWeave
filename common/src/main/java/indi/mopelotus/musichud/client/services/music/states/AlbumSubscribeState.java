package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;

public class AlbumSubscribeState extends SubscribeState<Album> {
    private static final MusicService musicService = MusicService.getInstance();
    private static final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();

    public AlbumSubscribeState(long id) {
        super(id, Album.class,
                (id1) -> musicService.loadAlbumDetail(id1, false),
                () -> musicService.loadAccountAlbums(tuneWeave.platformOfEntity(Album.class, id), false, ignored -> {}).thenApply(albums -> albums),
                () -> tuneWeave.prepareBiConsumer(tuneWeave::setAlbumSubscribed)
        );
    }
}
