package indi.mopelotus.musichud.network.payloads.pushMessages.s2c;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.PlaybackSession;
import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.IClientMusicService;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.platform.Environment;

public record SyncCurrentPlayingMessage(PlaybackSession playbackSession, MusicDetail nextIdle) implements S2CPayload {
    public static final ByteBufCodec<SyncCurrentPlayingMessage> CODEC = ByteBufCodec.composite(
            PlaybackSession.CODEC,
            SyncCurrentPlayingMessage::playbackSession,
            MusicDetail.CODEC,
            SyncCurrentPlayingMessage::nextIdle,
            SyncCurrentPlayingMessage::new
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        @Override
        public void register() {
            NetworkReceiver<SyncCurrentPlayingMessage> receiver = NetworkReceiver.noop();
            if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
                receiver = (message, player) -> indi.mopelotus.musichud.network.ClientPacketContext.execute(MusicHud.EXECUTOR,() -> {
                    IClientMusicService musicService = IClientMusicService.getInstance();
                    musicService.switchMusic(message.playbackSession, message.nextIdle, "");
                });
            }
            INetworkRegister.getInstance().autoRegisterPayload(SyncCurrentPlayingMessage.class, CODEC, receiver);
        }
    }
}
