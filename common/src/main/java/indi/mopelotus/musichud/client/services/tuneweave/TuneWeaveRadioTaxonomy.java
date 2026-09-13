package indi.mopelotus.musichud.client.services.tuneweave;

import java.util.List;

public record TuneWeaveRadioTaxonomy(List<TuneWeaveRadioOption> categories,
                                     List<TuneWeaveRadioOption> regions) {
    public TuneWeaveRadioTaxonomy {
        categories = categories == null ? List.of() : List.copyOf(categories);
        regions = regions == null ? List.of() : List.copyOf(regions);
    }
}
