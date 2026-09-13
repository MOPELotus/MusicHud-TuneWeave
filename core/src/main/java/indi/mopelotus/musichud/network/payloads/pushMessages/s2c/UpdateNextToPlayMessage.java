package indi.mopelotus.musichud.network.payloads.pushMessages.s2c;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.IdlePreview;
import indi.mopelotus.musichud.interfaces.*;
import indi.mopelotus.musichud.network.*;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.platform.Environment;

public record UpdateNextToPlayMessage(IdlePreview preview) implements S2CPayload {
    public static final ByteBufCodec<UpdateNextToPlayMessage> CODEC = ByteBufCodec.composite(
            IdlePreview.CODEC, UpdateNextToPlayMessage::preview, UpdateNextToPlayMessage::new);
    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            NetworkReceiver<UpdateNextToPlayMessage> receiver = NetworkReceiver.noop();
            if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
                receiver = (message, player) -> MusicHud.EXECUTOR.execute(
                        () -> IClientMusicService.getInstance().updateNextToPlay(message.preview()));
            }
            INetworkRegister.getInstance().autoRegisterPayload(UpdateNextToPlayMessage.class, CODEC, receiver);
        }
    }
}
