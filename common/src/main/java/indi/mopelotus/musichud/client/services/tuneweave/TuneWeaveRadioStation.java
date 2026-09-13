package indi.mopelotus.musichud.client.services.tuneweave;

public record TuneWeaveRadioStation(String reference, String name, String description,
                                    String coverUrl, String category, String region,
                                    String currentProgram, boolean subscribed) {
}
