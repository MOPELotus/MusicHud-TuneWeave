package indi.mopelotus.musichud.network;

import java.util.Arrays;

/** Public plugin-presence probe, used only between a proxy and its backend. */
public final class ProxyDeploymentProbe {
    public static final String CHANNEL = "musichud_tuneweave:deployment_probe";
    private static final byte[] REQUEST = {77, 84, 87, 1, 0};
    private static final byte[] PRESENT = {77, 84, 87, 1, 1};

    private ProxyDeploymentProbe() {}

    public static byte[] request() { return REQUEST.clone(); }
    public static byte[] present() { return PRESENT.clone(); }
    public static boolean isRequest(byte[] bytes) { return Arrays.equals(REQUEST, bytes); }
    public static boolean isPresent(byte[] bytes) { return Arrays.equals(PRESENT, bytes); }
}
