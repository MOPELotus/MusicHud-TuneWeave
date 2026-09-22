package indi.mopelotus.musichud.client.ui.dto;

import indi.mopelotus.musichud.client.services.cloud.CloudUploadTask;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveCloudTrack;

public record CloudTrackEntry(long id, TuneWeaveCloudTrack cloudTrackInfo, CloudUploadTask task) {
    public CloudEntryState getState() {
        return task == null ? CloudEntryState.UPLOADED : task.getState();
    }
}
