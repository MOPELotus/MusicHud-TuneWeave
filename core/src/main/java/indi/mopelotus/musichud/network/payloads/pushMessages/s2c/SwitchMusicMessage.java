package indi.mopelotus.musichud.network.payloads.pushMessages.s2c;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.PlaybackSession;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.IClientMusicService;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.platform.Environment;
import indi.mopelotus.musichud.utils.IClientDistUtil;

public record SwitchMusicMessage(PlaybackSession playbackSession, MusicDetail nextIdle, String message, long previewRevision) implements S2CPayload {
    public SwitchMusicMessage {
        if (previewRevision < 0) throw new IllegalArgumentException("Negative preview revision");
    }
    public SwitchMusicMessage(PlaybackSession session, MusicDetail next, String message) {
        this(session, next, message, 0);
    }
    public static final ByteBufCodec<SwitchMusicMessage> CODEC = ByteBufCodec.composite(
            PlaybackSession.CODEC,
            SwitchMusicMessage::playbackSession,
            MusicDetail.CODEC,
            SwitchMusicMessage::nextIdle,
            Codecs.STRING_UTF8,
            SwitchMusicMessage::message,
            indi.mopelotus.musichud.network.Codecs.LONG,
            SwitchMusicMessage::previewRevision,
            SwitchMusicMessage::new
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        private static ClientConfig clientConfig;
        static {
            if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
                try {
                    clientConfig = ClientConfig.getInstance();
                } catch (UnsupportedOperationException e) {
                    clientConfig = null;
                }
            }
        }

        public void register() {
            NetworkReceiver<SwitchMusicMessage> receiver = NetworkReceiver.noop();
            if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
                receiver = (message, player) -> {
                    indi.mopelotus.musichud.network.ClientPacketContext.execute(MusicHud.EXECUTOR,() -> {
                        if (!clientConfig.getEnable()) {
                            return;
                        }
                        String message1 = message.message;
                        if (message1.startsWith(MusicHud.MOD_ID + ".")) {
                            message1 = IClientDistUtil.getInstance().getI18n(message1);
                        }
                        IClientMusicService musicService = IClientMusicService.getInstance();
                        musicService.switchMusic(message.playbackSession, message.nextIdle, message1, message.previewRevision);
                    });
                };
            }
            INetworkRegister.getInstance().autoRegisterPayload(
                    SwitchMusicMessage.class, CODEC,
                    receiver
            );
        }
    }
}
