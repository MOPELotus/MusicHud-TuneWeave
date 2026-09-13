package indi.mopelotus.musichud.client.services.tuneweave;

public record TuneWeaveQrPoll(String state, String message, TuneWeaveSession profile) {
    public boolean terminal() {
        return "confirmed".equals(state) || "expired".equals(state) || "failed".equals(state);
    }
}
