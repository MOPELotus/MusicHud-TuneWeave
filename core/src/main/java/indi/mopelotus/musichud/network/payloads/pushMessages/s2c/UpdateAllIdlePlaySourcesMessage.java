package indi.mopelotus.musichud.network.payloads.pushMessages.s2c;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.IClientMusicService;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.platform.Environment;

import java.util.List;

public record UpdateAllIdlePlaySourcesMessage(List<Playlist> playlistSources,
                                              List<Album> albumSources) implements S2CPayload {
    public static final ByteBufCodec<UpdateAllIdlePlaySourcesMessage> CODEC = ByteBufCodec.composite(
            Codecs.ofList(() -> Playlist.CODEC),
            UpdateAllIdlePlaySourcesMessage::playlistSources,
            Codecs.ofList(() -> Album.CODEC),
            UpdateAllIdlePlaySourcesMessage::albumSources,
            UpdateAllIdlePlaySourcesMessage::new
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        @Override
        public void register() {
            NetworkReceiver<UpdateAllIdlePlaySourcesMessage> receiver = NetworkReceiver.noop();
            if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
                receiver = (playSourcesMessage, packetContext) ->
                        IClientMusicService.getInstance().getIdlePlaySourceState().external().updateAll(
                                playSourcesMessage.playlistSources,
                                playSourcesMessage.albumSources
                        );
            }
            INetworkRegister.getInstance().autoRegisterPayload(
                    UpdateAllIdlePlaySourcesMessage.class,
                    CODEC,
                    receiver
            );
        }
    }
}
