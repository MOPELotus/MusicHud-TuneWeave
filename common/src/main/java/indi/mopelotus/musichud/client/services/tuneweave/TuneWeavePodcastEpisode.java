package indi.mopelotus.musichud.client.services.tuneweave;

public record TuneWeavePodcastEpisode(String reference, String podcastReference, String name,
                                      String description, String coverUrl, String creatorName,
                                      String audioReference, int durationMillis, String publishedAt,
                                      long serialNumber, boolean hasLyrics) {
}
