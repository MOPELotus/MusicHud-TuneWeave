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

public record SyncCurrentPlayingMessage(PlaybackSession playbackSession, MusicDetail nextIdle, long previewRevision) implements S2CPayload {
    public SyncCurrentPlayingMessage {
        if (previewRevision < 0) throw new IllegalArgumentException("Negative preview revision");
    }
    public SyncCurrentPlayingMessage(PlaybackSession session, MusicDetail next) {
        this(session, next, 0);
    }
    public static final ByteBufCodec<SyncCurrentPlayingMessage> CODEC = ByteBufCodec.composite(
            PlaybackSession.CODEC,
            SyncCurrentPlayingMessage::playbackSession,
            MusicDetail.CODEC,
            SyncCurrentPlayingMessage::nextIdle,
            indi.mopelotus.musichud.network.Codecs.LONG,
            SyncCurrentPlayingMessage::previewRevision,
            SyncCurrentPlayingMessage::new
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        @Override
        public void register() {
            NetworkReceiver<SyncCurrentPlayingMessage> receiver = NetworkReceiver.noop();
            if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
                receiver = (message, player) -> MusicHud.EXECUTOR.execute(() -> {
                    IClientMusicService musicService = IClientMusicService.getInstance();
                    musicService.switchMusic(message.playbackSession, message.nextIdle, "", message.previewRevision);
                });
            }
            INetworkRegister.getInstance().autoRegisterPayload(SyncCurrentPlayingMessage.class, CODEC, receiver);
        }
    }
}
