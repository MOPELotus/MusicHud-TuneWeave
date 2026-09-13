package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.beans.music.MusicDetail;

public record TuneWeaveCloudTrack(String reference, MusicDetail track, String filename,
                                  long fileSize, String fileType, long bitrate, String md5,
                                  String addedAt, String matchedTrackReference) {
}
