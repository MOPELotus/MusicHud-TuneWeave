package indi.mopelotus.musichud.client.audio;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** Exception messages can contain signed URLs and headers; log types and code locations only. */
public final class PlaybackFailureDiagnostics {
    private PlaybackFailureDiagnostics() {}

    public static String describe(Throwable failure) {
        StringBuilder result = new StringBuilder();
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.size() < 8 && visited.add(cause);
             cause = cause.getCause()) {
            if (!result.isEmpty()) result.append(" <- ");
            result.append(cause.getClass().getName());
            StackTraceElement[] trace = cause.getStackTrace();
            for (int i = 0; i < Math.min(trace.length, 8); i++) result.append(" at ").append(trace[i]);
        }
        return result.toString();
    }

    public static String kind(Throwable failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.size() < 8 && visited.add(cause);
             cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException) return "timeout";
            if (cause instanceof CancellationException || cause instanceof InterruptedException) return "cancelled";
        }
        return "failure";
    }
}
