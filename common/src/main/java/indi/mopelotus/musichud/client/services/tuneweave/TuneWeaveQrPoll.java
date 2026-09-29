package indi.mopelotus.musichud.client.services.tuneweave;

public record TuneWeaveQrPoll(String state, String message, TuneWeaveSession profile, TuneWeaveLoginProgress progress) {
    public TuneWeaveQrPoll(String state, String message, TuneWeaveSession profile) {
        this(state, message, profile, new TuneWeaveLoginProgress(state, null, java.util.List.of(), profile));
    }
    @Override public String toString() { return "TuneWeaveQrPoll[state=" + state + "]"; }
    public boolean terminal() {
        return "confirmed".equals(state) || "expired".equals(state) || "failed".equals(state);
    }
}
