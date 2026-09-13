package indi.mopelotus.musichud.client.services.tuneweave;

public record TuneWeavePodcast(String reference, String name, String description, String coverUrl,
                               String creatorName, String category, String secondaryCategory,
                               long episodeCount, long subscriberCount, long playCount,
                               boolean subscribed) {
}
