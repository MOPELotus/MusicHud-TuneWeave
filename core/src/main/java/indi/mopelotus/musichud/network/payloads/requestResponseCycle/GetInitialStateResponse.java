package indi.mopelotus.musichud.network.payloads.requestResponseCycle;

import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.PlaybackSession;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.music.QueueItem;
import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.network.RequestResponseCodecs;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.RequestResponseManager;
import indi.mopelotus.musichud.network.payloads.ApiResponsePayload;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Queue;

@Getter
@AllArgsConstructor
public class GetInitialStateResponse extends ApiResponsePayload {
    public static final ByteBufCodec<GetInitialStateResponse> CODEC =
            RequestResponseCodecs.withCycleId(
                    ByteBufCodec.composite(
                            PlaybackSession.CODEC,
                            GetInitialStateResponse::getPlaybackSession,
                            MusicDetail.CODEC,
                            GetInitialStateResponse::getNextIdle,
                            Codecs.ofQueue(() -> QueueItem.CODEC),
                            GetInitialStateResponse::getQueue,
                            Codecs.ofList(() -> Playlist.CODEC),
                            GetInitialStateResponse::getPlaylistSources,
                            Codecs.ofList(() -> Album.CODEC),
                            GetInitialStateResponse::getAlbumSources,
                            GetInitialStateResponse::new
                    )
            );

    private final PlaybackSession playbackSession;
    private final MusicDetail nextIdle;
    private final Queue<QueueItem> queue;
    private final List<Playlist> playlistSources;
    private final List<Album> albumSources;

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    GetInitialStateResponse.class, CODEC,
                    (response, player) -> RequestResponseManager.complete(response)
            );
        }
    }
}
