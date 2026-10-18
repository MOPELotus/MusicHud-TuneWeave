package indi.mopelotus.musichud.client.update;

import indi.mopelotus.musichud.server.api.ApiBinaryUpdateService;
import indi.mopelotus.musichud.server.api.TuneWeaveCompatibility;

final class ServiceUpdateCompatibility {
    private ServiceUpdateCompatibility() {}
    static ServiceUpdateService.Status evaluate(ApiBinaryUpdateService.UpdateOffer offer) {
        return evaluate(offer.manifestJson(), offer.targetVersion());
    }
    static ServiceUpdateService.Status evaluate(String manifest, String version) {
        return switch (TuneWeaveCompatibility.evaluate(manifest, version)) {
            case COMPATIBLE -> ServiceUpdateService.Status.AVAILABLE;
            case UNKNOWN -> ServiceUpdateService.Status.UNKNOWN;
            case INCOMPATIBLE -> ServiceUpdateService.Status.INCOMPATIBLE;
        };
    }
}
