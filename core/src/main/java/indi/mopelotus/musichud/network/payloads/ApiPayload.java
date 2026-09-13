package indi.mopelotus.musichud.network.payloads;

import java.util.UUID;

public interface ApiPayload extends IPayload {
    UUID getRequestId();

    void setRequestId(UUID requestId);
}
