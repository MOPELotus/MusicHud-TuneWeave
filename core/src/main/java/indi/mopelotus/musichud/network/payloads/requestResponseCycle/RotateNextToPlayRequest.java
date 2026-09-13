package indi.mopelotus.musichud.network.payloads.requestResponseCycle;

import indi.mopelotus.musichud.interfaces.*;
import indi.mopelotus.musichud.network.*;
import indi.mopelotus.musichud.network.payloads.ApiRequestPayload;
import indi.mopelotus.musichud.server.api.MusicPlayerServerService;
import lombok.Getter;
import java.util.Objects;
import java.util.UUID;

@Getter
public class RotateNextToPlayRequest extends ApiRequestPayload {
    private final UUID sessionId;
    private final long sequence;
    private final long previewRevision;
    public RotateNextToPlayRequest(UUID sessionId, long sequence, long previewRevision) {
        this.sessionId = Objects.requireNonNull(sessionId);
        if (sequence < 0 || previewRevision < 0) throw new IllegalArgumentException("Negative preview identity");
        this.sequence = sequence;
        this.previewRevision = previewRevision;
    }
    public static final ByteBufCodec<RotateNextToPlayRequest> CODEC = RequestResponseCodecs.withCycleId(
            ByteBufCodec.composite(Codecs.UUID, RotateNextToPlayRequest::getSessionId,
                    Codecs.LONG, RotateNextToPlayRequest::getSequence,
                    Codecs.LONG, RotateNextToPlayRequest::getPreviewRevision, RotateNextToPlayRequest::new));
    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            RequestHandlerRegistry.autoRegisterPayload(RotateNextToPlayRequest.class, CODEC, (request, player) ->
                    ResponseResult.of(new RotateNextToPlayResponse(
                            MusicPlayerServerService.getInstance().rotateNextToPlay(player, request))));
        }
    }
}
