package indi.mopelotus.musichud.client.services.cloud;

import indi.mopelotus.musichud.client.ui.dto.CloudEntryState;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;

/** Mutable client-side state of one queued cloud upload. */
@Getter
@Setter
public final class CloudUploadTask {
    private final long id;
    private final Path file;
    private final String fileName;

    private volatile CloudEntryState state = CloudEntryState.QUEUED;
    private volatile boolean cancelRequested;

    private volatile long fileSize;
    private volatile long bytesUploaded;
    private volatile int durationMillis;
    /** File metadata has been read for this attempt. */
    private volatile boolean prepared;
    private volatile long bitrate = 999_000;
    private volatile long attempt;
    private Object accountScope;
    private CloudUploadService.PreparedUpload preparedUpload;
    private boolean retryRequested;

    private volatile String song = "";
    private volatile String artist = "";
    private volatile String album = "";
    /** {@code data:image/...;base64,...} from embedded artwork, or null for the icon fallback. */
    private volatile String coverDataUri;

    private volatile String errorMessage = "";
    /** Normalized track reference returned by the completion response. */
    private volatile String resolvedTrackId;

    public CloudUploadTask(long id, Path file) {
        this.id = id;
        this.file = file;
        this.fileName = file.getFileName().toString();
    }
}
