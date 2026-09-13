package indi.mopelotus.musichud.server;

import indi.mopelotus.musichud.interfaces.ServerConfig;

/** Server/proxy policy: providers, credentials and managed TuneWeave processes stay on clients. */
public abstract class ClientResolvedServerConfig implements ServerConfig {
    private volatile double voteRate = .5;
    private volatile boolean configured;
    public String getServerApiBaseUrl() { return "http://127.0.0.1:7832"; }
    public void setServerApiBaseUrl(String ignored) { throw clientOwned(); }
    public boolean getStartupBinaryApiServerWhenLaunch() { return false; }
    public void setStartupBinaryApiServerWhenLaunch(boolean enabled) { if (enabled) throw clientOwned(); }
    public String getServerApiBinaryExecutablePath() { return ""; }
    public void setServerApiBinaryExecutablePath(String ignored) { throw clientOwned(); }
    public double getPusherVoteAdditionalRate() { return voteRate; }
    public void setPusherVoteAdditionalRate(double value) { voteRate = Double.isFinite(value) ? Math.clamp(value, 0, 1) : .5; }
    public int getPort() { return 7832; }
    public void setPort(int ignored) { throw clientOwned(); }
    public boolean isConfigured() { return configured; }
    public void setConfigured(boolean configured) { this.configured = configured; }
    private static UnsupportedOperationException clientOwned() { return new UnsupportedOperationException("TuneWeave is client-owned"); }
}
