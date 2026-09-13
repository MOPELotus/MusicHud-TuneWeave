package indi.mopelotus.musichud.network.payloads;

import java.util.UUID;

public interface IResponsePayload extends S2CPayload {
    UUID getRequestId();
}
