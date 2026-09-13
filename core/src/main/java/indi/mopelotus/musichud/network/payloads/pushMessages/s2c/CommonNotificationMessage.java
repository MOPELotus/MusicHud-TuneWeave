package indi.mopelotus.musichud.network.payloads.pushMessages.s2c;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.actions.MessagedResult;
import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.utils.IClientDistUtil;

public record CommonNotificationMessage(MessagedResult<Void> messagedResult) implements S2CPayload {
    public static final ByteBufCodec<CommonNotificationMessage> CODEC = ByteBufCodec.composite(
            MessagedResult.codec(Codecs.VOID), CommonNotificationMessage::messagedResult,
            CommonNotificationMessage::new
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        @Override
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    CommonNotificationMessage.class, CODEC,
                    ((payload, player) -> {
                        String message = payload.messagedResult.message();
                        IClientDistUtil clientDistUtil = IClientDistUtil.getInstance();
                        if (message.startsWith(MusicHud.MOD_ID + ".")) {
                            message = clientDistUtil.getI18n(message);
                        }
                        clientDistUtil.showToast(message);
                    })
            );
        }
    }
}