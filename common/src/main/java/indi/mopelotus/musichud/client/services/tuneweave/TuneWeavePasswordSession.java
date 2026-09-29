package indi.mopelotus.musichud.client.services.tuneweave;
/** Opaque password challenge identity; never stores the password. */
public record TuneWeavePasswordSession(String transactionId, TuneWeaveLoginProgress progress) {
    @Override public String toString() { return "TuneWeavePasswordSession[state=" + progress.state() + "]"; }
}
