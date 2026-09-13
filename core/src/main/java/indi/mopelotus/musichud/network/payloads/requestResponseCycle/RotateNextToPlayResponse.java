package indi.mopelotus.musichud.network.payloads.requestResponseCycle;

import indi.mopelotus.musichud.beans.music.actions.MessagedResult;
import indi.mopelotus.musichud.interfaces.*;
import indi.mopelotus.musichud.network.*;
import indi.mopelotus.musichud.network.payloads.ApiResponsePayload;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RotateNextToPlayResponse extends ApiResponsePayload {
    private final MessagedResult<Boolean> result;
    public static final ByteBufCodec<RotateNextToPlayResponse> CODEC = RequestResponseCodecs.withCycleId(
            ByteBufCodec.composite(MessagedResult.codec(Codecs.BOOL), RotateNextToPlayResponse::getResult,
                    RotateNextToPlayResponse::new));
    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(RotateNextToPlayResponse.class, CODEC,
                    (response, player) -> RequestResponseManager.complete(response));
        }
    }
}
