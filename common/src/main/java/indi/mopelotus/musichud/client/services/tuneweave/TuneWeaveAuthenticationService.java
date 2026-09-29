package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

import java.util.Map;
import java.util.Objects;

import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.object;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.requiredString;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.string;

/** Owns authentication flows and the authenticated profile cache for each platform. */
final class TuneWeaveAuthenticationService {
    private final TuneWeaveGateway gateway;
    private final Map<TuneWeavePlatform, TuneWeaveSession> sessions;
    private final Map<TuneWeavePlatform, TuneWeaveLoginAttempt> attempts = new java.util.EnumMap<>(TuneWeavePlatform.class);
    private final Map<TuneWeavePlatform, Object> accountEpochs = new java.util.EnumMap<>(TuneWeavePlatform.class);
    private final Map<Object, TuneWeaveLoginAttempt> transactions = new java.util.IdentityHashMap<>();

    synchronized TuneWeaveLoginAttempt beginLogin(TuneWeavePlatform platform) {
        cancelPlatform(platform);
        var validation = gateway.capture(() -> null);
        var attempt = new TuneWeaveLoginAttempt(platform, gateway.credential(platform), () -> validation.get());
        attempt.accountIdentity = gateway.accountIdentity(platform);
        attempts.put(platform, attempt);
        return attempt;
    }

    synchronized void cancelLogin(TuneWeaveLoginAttempt attempt) {
        if (attempt != null && attempts.get(attempt.platform) == attempt) cancelPlatform(attempt.platform);
    }

    synchronized void cancelLogins() { for (var platform : TuneWeavePlatform.values()) cancelPlatform(platform); }

    private synchronized void cancelPlatform(TuneWeavePlatform platform) {
        accountEpochs.put(platform, new Object());
        attempts.remove(platform);
        transactions.values().removeIf(attempt -> attempt.platform == platform);
    }

    synchronized boolean isCurrent(TuneWeaveLoginAttempt attempt) {
        try { requireCurrent(attempt); return true; }
        catch (java.util.concurrent.CancellationException error) { return false; }
    }

    synchronized void publishLogin(TuneWeaveLoginAttempt attempt, Runnable publish) {
        requireCurrent(attempt); publish.run();
    }

    private synchronized void requireCurrent(TuneWeaveLoginAttempt attempt) {
        if (attempt == null || attempts.get(attempt.platform) != attempt)
            throw new java.util.concurrent.CancellationException("Login attempt expired");
        attempt.validateContext.run();
    }

    private synchronized TuneWeaveLoginAttempt transaction(Object session) {
        var attempt = transactions.get(session);
        requireCurrent(attempt);
        return attempt;
    }

    private synchronized <T> T bind(TuneWeaveLoginAttempt attempt, T session) {
        requireCurrent(attempt); transactions.put(session, attempt); return session;
    }

    private TuneWeaveSession commit(TuneWeaveLoginAttempt attempt, JsonElement credential) {
        synchronized (this) {
            requireCurrent(attempt);
            gateway.publishIfAccountCurrent(attempt.platform, attempt.accountIdentity,
                    () -> { gateway.saveCredential(attempt.platform, credential); sessions.remove(attempt.platform); });
            attempt.expectedCredential = gateway.credential(attempt.platform);
            attempt.accountIdentity = gateway.accountIdentity(attempt.platform);
            var validation = gateway.capture(() -> null);
            attempt.validateContext = () -> validation.get();
            transactions.values().removeIf(value -> value == attempt);
        }
        TuneWeaveSession result = loadSession(attempt.platform);
        requireCurrent(attempt);
        return result;
    }

    TuneWeaveAuthenticationService(TuneWeaveGateway gateway,
                                   Map<TuneWeavePlatform, TuneWeaveSession> sessions) {
        this.gateway = Objects.requireNonNull(gateway);
        this.sessions = Objects.requireNonNull(sessions);
    }

    TuneWeaveSession cachedSession(TuneWeavePlatform platform) {
        return gateway.hasCredential(platform) ? sessions.get(platform) : null;
    }

    TuneWeaveQrSession startQrLogin(TuneWeavePlatform platform, String loginType) {
        return startQrLogin(beginLogin(platform), loginType);
    }

    TuneWeaveQrSession startQrLogin(TuneWeaveLoginAttempt attempt, String loginType) {
        requireCurrent(attempt);
        TuneWeavePlatform platform = attempt.platform;
        JsonObject body = clientModeBody(platform);
        if (loginType != null && !loginType.isBlank()) {
            body.addProperty("login_type", loginType.trim());
        }
        JsonObject data = object(gateway.requestWithoutCredential(
                "POST", "/v1/auth/qr", Map.of(), body).data());
        return bind(attempt, new TuneWeaveQrSession(platform, requiredString(data, "transaction_id"),
                string(data, "url"), string(data, "image_data_url"), string(data, "expires_at")));
    }

    TuneWeaveQrPoll pollQrLogin(TuneWeaveQrSession session) {
        var attempt = transaction(session);
        JsonObject data = object(gateway.requestWithoutCredential(
                "GET", "/v1/auth/qr/" + TuneWeaveApiClient.encodePathSegment(session.transactionId()),
                Map.of(), null).data());
        return qrProgress(attempt, data);
    }

    TuneWeaveQrPoll verifyQrLogin(TuneWeaveQrSession session, JsonObject action) {
        var attempt = transaction(session);
        JsonObject data = object(gateway.requestWithoutCredential("POST", "/v1/auth/qr/"
                + TuneWeaveApiClient.encodePathSegment(session.transactionId()) + "/verification", Map.of(), action).data());
        return qrProgress(attempt, data);
    }

    private TuneWeaveQrPoll qrProgress(TuneWeaveLoginAttempt attempt, JsonObject data) {
        requireCurrent(attempt);
        var progress = TuneWeaveLoginProgress.read(data, null);
        TuneWeaveSession profile = "confirmed".equals(progress.state()) ? commit(attempt, data.get("caller_credential")) : null;
        return new TuneWeaveQrPoll(progress.state(), string(data, "message"), profile, progress);
    }

    TuneWeaveSession importCredential(TuneWeaveLoginAttempt attempt, String cookie) {
        requireCurrent(attempt);
        if (cookie == null || cookie.isBlank() || cookie.length() > 65_536 || cookie.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("Invalid credential input");
        JsonObject body = clientModeBody(attempt.platform);
        JsonObject credential = new JsonObject();
        credential.addProperty("kind", "cookie"); credential.addProperty("value", cookie);
        body.add("credential", credential);
        JsonObject data = object(gateway.requestWithoutCredential("POST", "/v1/auth/import", Map.of(), body).data());
        return commit(attempt, data.get("caller_credential"));
    }

    TuneWeavePasswordSession startPasswordLogin(TuneWeaveLoginAttempt attempt, String principalType,
                                                String principal, String password) {
        requireCurrent(attempt);
        if (attempt.platform != TuneWeavePlatform.KUWO && attempt.platform != TuneWeavePlatform.MIGU)
            throw new IllegalArgumentException("Unsupported password login platform");
        if (!java.util.Set.of("username", "phone", "email").contains(principalType)
                || principal == null || principal.isBlank() || principal.length() > 512
                || password == null || password.isBlank() || password.length() > 4096)
            throw new IllegalArgumentException("Invalid password login input");
        JsonObject body = clientModeBody(attempt.platform);
        body.addProperty("principal_type", principalType); body.addProperty("principal", principal);
        body.addProperty("password", password); body.addProperty("password_format", "plain");
        if ("phone".equals(principalType)) body.addProperty("country_code", "86");
        JsonObject data = object(gateway.requestWithoutCredential("POST", "/v1/auth/password", Map.of(), body).data());
        var progress = passwordProgress(attempt, data);
        if ("confirmed".equals(progress.state())) return new TuneWeavePasswordSession(null, progress);
        return bind(attempt, new TuneWeavePasswordSession(requiredString(data, "transaction_id"), progress));
    }

    TuneWeaveLoginProgress advancePasswordLogin(TuneWeavePasswordSession session, JsonObject action) {
        var attempt = transaction(session);
        JsonObject data = object(gateway.requestWithoutCredential("POST", "/v1/auth/password/challenges/"
                + TuneWeaveApiClient.encodePathSegment(session.transactionId()) + "/verify", Map.of(), action).data());
        return passwordProgress(attempt, data);
    }

    private TuneWeaveLoginProgress passwordProgress(TuneWeaveLoginAttempt attempt, JsonObject data) {
        requireCurrent(attempt);
        if (data.has("caller_credential") && !data.get("caller_credential").isJsonNull()) {
            if (data.has("state") && !"confirmed".equals(requiredString(data, "state")))
                throw new IllegalArgumentException("Credential in unfinished password login");
            return new TuneWeaveLoginProgress("confirmed", null, java.util.List.of(), commit(attempt, data.get("caller_credential")));
        }
        var progress = TuneWeaveLoginProgress.read(data, null);
        if (!"verification_required".equals(progress.state()))
            throw new IllegalArgumentException("Invalid password login progress");
        return progress;
    }

    TuneWeaveSession loginWithPassword(TuneWeavePlatform platform, String principalType,
                                       String principal, String password, String passwordFormat,
                                       String countryCode) {
        var attempt = beginLogin(platform);
        JsonObject body = clientModeBody(platform);
        body.addProperty("principal_type", principalType);
        body.addProperty("principal", principal);
        body.addProperty("password", password);
        if (passwordFormat != null && !passwordFormat.isBlank()) {
            body.addProperty("password_format", passwordFormat);
        }
        if (countryCode != null && !countryCode.isBlank()) {
            body.addProperty("country_code", countryCode);
        }
        JsonObject data = object(gateway.requestWithoutCredential(
                "POST", "/v1/auth/password", Map.of(), body).data());
        return commit(attempt, data.get("caller_credential"));
    }

    TuneWeaveChallengeSession startSmsLogin(TuneWeavePlatform platform, String principal,
                                             String countryCode) {
        return startSmsLogin(beginLogin(platform), principal, countryCode);
    }

    TuneWeaveChallengeSession startSmsLogin(TuneWeaveLoginAttempt attempt, String principal, String countryCode) {
        return startSmsLogin(attempt, principal, countryCode, false);
    }

    TuneWeaveChallengeSession startSmsLogin(TuneWeaveLoginAttempt attempt, String principal, String countryCode, boolean allowAccountCreation) {
        requireCurrent(attempt);
        TuneWeavePlatform platform = attempt.platform;
        JsonObject body = clientModeBody(platform);
        body.addProperty("method", "sms");
        if (platform == TuneWeavePlatform.KUWO || platform == TuneWeavePlatform.KUGOU) {
            body.addProperty("backend", "standard");
            body.addProperty("allow_account_creation", allowAccountCreation);
        } else if (allowAccountCreation) throw new IllegalArgumentException("Unexpected account creation option");
        body.addProperty("principal", principal);
        body.addProperty("country_code", countryCode == null || countryCode.isBlank() ? "86" : countryCode);
        JsonObject data = object(gateway.requestWithoutCredential(
                "POST", "/v1/auth/challenges", Map.of(), body).data());
        return bind(attempt, new TuneWeaveChallengeSession(platform, requiredString(data, "transaction_id"), TuneWeaveLoginProgress.read(data, null)));
    }

    TuneWeaveSession verifySmsLogin(TuneWeaveChallengeSession session, String code) {
        JsonObject body = new JsonObject(); body.addProperty("code", code);
        var progress = advanceSmsLogin(session, body);
        if (!"confirmed".equals(progress.state())) throw new IllegalStateException("Additional login verification required");
        return progress.profile();
    }

    TuneWeaveLoginProgress advanceSmsLogin(TuneWeaveChallengeSession session, JsonObject body) {
        var attempt = transaction(session);
        JsonObject data = object(gateway.requestWithoutCredential("POST", "/v1/auth/challenges/"
                + TuneWeaveApiClient.encodePathSegment(session.transactionId()) + "/verify", Map.of(), body).data());
        requireCurrent(attempt);
        var progress = TuneWeaveLoginProgress.read(data, null);
        if (!"confirmed".equals(progress.state())) return progress;
        return new TuneWeaveLoginProgress(progress.state(), null, java.util.List.of(), commit(attempt, data.get("caller_credential")));
    }

    TuneWeaveSession loadSession(TuneWeavePlatform platform) {
        Object expectedEpoch;
        synchronized (this) { expectedEpoch = accountEpochs.get(platform); }
        Object expected = gateway.accountIdentity(platform);
        return gateway.capture(() -> {
            JsonElement data = gateway.requestForPlatform(platform, "GET", "/v1/auth/session",
                    Map.of("platform", platform.apiName()), null).data();
            TuneWeaveSession parsed = profile(data);
            if (parsed != null && parsed.platform() != platform)
                throw new TuneWeaveApiClient.TuneWeaveException("Session platform does not match requested platform", false);
            TuneWeaveSession result = enrichSessionProfile(parsed);
            synchronized (this) {
                if (accountEpochs.get(platform) != expectedEpoch) throw new java.util.concurrent.CancellationException("Authentication state changed during profile load");
                gateway.publishIfAccountCurrent(platform, expected, () -> {
                    if (result != null) sessions.put(platform, result);
                });
            }
            return result;
        }).get();
    }

    TuneWeaveSession refreshSession(TuneWeavePlatform platform) {
        var attempt = beginLogin(platform);
        JsonObject body = clientModeBody(platform);
        JsonObject data = object(gateway.requestForPlatform(
                platform, "POST", "/v1/auth/session/refresh", Map.of(), body).data());
        return commit(attempt, data.get("caller_credential"));
    }

    void logout(TuneWeavePlatform platform) {
        prepareLogout(platform).run();
    }

    synchronized Runnable prepareLogout(TuneWeavePlatform platform) {
        cancelPlatform(platform);
        Object logoutEpoch = accountEpochs.get(platform);
        String expected = gateway.credential(platform);
        Object identity = gateway.accountIdentity(platform);
        var request = gateway.capture(() -> gateway.requestForPlatform(platform, "DELETE", "/v1/auth/session",
                Map.of("platform", platform.apiName(), "credential_mode", "client"), null));
        return () -> {
            if (expected.isBlank()) return;
            synchronized (this) { if (accountEpochs.get(platform) != logoutEpoch) return; }
            try {
                request.get();
            } finally {
                synchronized (this) {
                    if (accountEpochs.get(platform) == logoutEpoch)
                        gateway.clearCredentialIfCurrent(platform, identity, () -> sessions.remove(platform));
                }
            }
        };
    }

    synchronized void clearCredential(TuneWeavePlatform platform) {
        cancelPlatform(platform);
        sessions.remove(platform);
        gateway.clearCredential(platform);
    }

    private TuneWeaveSession enrichSessionProfile(TuneWeaveSession session) {
        if (session == null || !session.authenticated()
                || (!isBlank(session.nickname()) && !isBlank(session.avatarUrl()))) {
            return session;
        }
        try {
            JsonObject detail = object(gateway.requestForPlatform(
                    session.platform(), "GET", "/v1/account/profile",
                    Map.of("platform", session.platform().apiName()), null).data());
            JsonObject user = detail.has("user") && detail.get("user").isJsonObject()
                    ? detail.getAsJsonObject("user") : new JsonObject();
            return new TuneWeaveSession(session.platform(),
                    prefer(session.userId(), string(user, "id")),
                    prefer(session.nickname(), string(user, "name")),
                    prefer(session.avatarUrl(), string(user, "avatar_url")),
                    session.authenticated());
        } catch (TuneWeaveApiClient.TuneWeaveException error) {
            if (!"capability_not_supported".equals(error.getCode())) {
                throw error;
            }
            return session;
        }
    }

    private static JsonObject clientModeBody(TuneWeavePlatform platform) {
        JsonObject body = new JsonObject();
        body.addProperty("platform", platform.apiName());
        body.addProperty("credential_mode", "client");
        return body;
    }

    private static TuneWeaveSession profile(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject value = object(element);
        JsonElement flag = value.get("authenticated");
        if (flag != null && (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()))
            throw new TuneWeaveApiClient.TuneWeaveException("Invalid session authentication flag", false);
        boolean authenticated = flag != null && flag.getAsBoolean();
        String userId = string(value, "user_id");
        if (authenticated && (isBlank(userId) || userId.length() > 512))
            throw new TuneWeaveApiClient.TuneWeaveException("Invalid authenticated session identity", false);
        return new TuneWeaveSession(
                TuneWeaveReference.requirePlatform(requiredString(value, "platform")),
                userId, string(value, "nickname"),
                string(value, "avatar_url"),
                authenticated);
    }

    private static String prefer(String primary, String fallback) {
        return isBlank(primary) ? Objects.requireNonNullElse(fallback, "") : primary;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
