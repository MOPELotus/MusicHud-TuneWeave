package indi.mopelotus.musichud.network.payloads.requestResponseCycle;

import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.RequestHandlerRegistry;
import indi.mopelotus.musichud.network.RequestResponseCodecs;
import indi.mopelotus.musichud.network.ResponseResult;
import indi.mopelotus.musichud.network.payloads.ApiRequestPayload;
import indi.mopelotus.musichud.server.api.MusicPlayerServerService;

public class GetInitialStateRequest extends ApiRequestPayload {
    public static final ByteBufCodec<GetInitialStateRequest> CODEC = RequestResponseCodecs.withCycleId(
            ByteBufCodec.composite(
                    ByteBufCodec.unit(0),
                    ignored -> 0,
                    ignored -> new GetInitialStateRequest()
            )
    );

    public GetInitialStateRequest() {
    }

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            RequestHandlerRegistry.autoRegisterPayload(GetInitialStateRequest.class, CODEC, (request, player) ->
                    ResponseResult.of(MusicPlayerServerService.getInstance().buildInitialStateFor(player))
            );
        }
    }
}
