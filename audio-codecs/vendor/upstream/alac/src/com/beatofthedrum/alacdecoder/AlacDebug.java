package com.beatofthedrum.alacdecoder;

class AlacDebug {

    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("com.beatofthedrom.alacdecoder.debug", "false"));

    static void println(String msg) {
        if (DEBUG) System.err.println(msg);
    }

}
