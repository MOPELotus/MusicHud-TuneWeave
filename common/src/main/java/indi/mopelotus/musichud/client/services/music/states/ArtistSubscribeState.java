package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;

public class ArtistSubscribeState extends SubscribeState<Artist> {
    private static final MusicService musicService = MusicService.getInstance();
    private static final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();

    public ArtistSubscribeState(long id) {
        super(id, Artist.class,
                (id1) -> musicService.loadArtist(id1, false),
                () -> musicService.loadAccountArtists(tuneWeave.platformOfEntity(Artist.class, id), false, ignored -> {}).thenApply(artists -> artists),
                () -> tuneWeave.prepareBiConsumer(tuneWeave::setArtistSubscribed)
        );
    }
}
