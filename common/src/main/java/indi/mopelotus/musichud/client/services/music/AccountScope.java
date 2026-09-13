package indi.mopelotus.musichud.client.services.music;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveSession;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import java.util.Objects;
public record AccountScope(String platform, String userId) {
    public static final AccountScope ANONYMOUS = new AccountScope("anonymous", "anonymous");
    public AccountScope {
        platform = normalize(platform); userId = normalize(userId);
        if (platform.isEmpty() || userId.isEmpty()) throw new IllegalArgumentException("Account scope values cannot be blank");
    }
    public static AccountScope fromSession(TuneWeaveSession session) {
        if (session == null || !session.authenticated()) return ANONYMOUS;
        return new AccountScope(session.platform().apiName(), session.userId());
    }
    private static String normalize(String value) { return Objects.requireNonNullElse(value, "").trim(); }
}
