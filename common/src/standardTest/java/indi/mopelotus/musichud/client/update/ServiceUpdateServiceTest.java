package indi.mopelotus.musichud.client.update;

import indi.mopelotus.musichud.server.api.ApiBinaryUpdateService;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ServiceUpdateServiceTest {
    static final String MANIFEST = "{\"schema_version\":1,\"project\":\"TuneWeave\",\"api_version\":\"v1\",\"compatibility_profiles\":[\"tuneweave-http-v1\"]}";
    static ApiBinaryUpdateService.UpdateOffer offer() {
        return new ApiBinaryUpdateService.UpdateOffer(Path.of("old"), "1.0.0-beta.1", "1.0.0-beta.2", "v1.0.0-beta.2", MANIFEST);
    }
    @Test void compatibilityUsesPublisherDeclarationInsteadOfSemverGuessing() {
        assertEquals(ServiceUpdateService.Status.AVAILABLE, ServiceUpdateCompatibility.evaluate(MANIFEST, "9.0.0"));
        String legacy = MANIFEST.replace(",\"compatibility_profiles\":[\"tuneweave-http-v1\"]", "");
        assertEquals(ServiceUpdateService.Status.AVAILABLE, ServiceUpdateCompatibility.evaluate(legacy, "1.0.0-beta.1"));
        assertEquals(ServiceUpdateService.Status.UNKNOWN, ServiceUpdateCompatibility.evaluate(legacy, "1.0.0-beta.2"));
        assertEquals(ServiceUpdateService.Status.UNKNOWN, ServiceUpdateCompatibility.evaluate(MANIFEST.replace("tuneweave-http-v1", "future-profile"), "1.0.0-beta.1"));
        assertEquals(ServiceUpdateService.Status.INCOMPATIBLE, ServiceUpdateCompatibility.evaluate(MANIFEST.replace("\"v1\"", "\"v2\""), "1.0.0"));
    }
    @Test void malformedCompatibilityCannotFallBackToHistoricalRelease() {
        for (String profiles : List.of("null", "{}", "[]", "[1]", "[\"tuneweave-http-v1\",\"tuneweave-http-v1\"]", "[\"../escape\"]")) {
            String manifest = MANIFEST.replace("[\"tuneweave-http-v1\"]", profiles);
            assertThrows(IllegalArgumentException.class, () -> ServiceUpdateCompatibility.evaluate(manifest, "1.0.0-beta.1"));
        }
        assertThrows(IllegalArgumentException.class, () -> ServiceUpdateCompatibility.evaluate(MANIFEST.replace("TuneWeave", "other"), "1.0.0-beta.1"));
    }
    @Test void supersededCheckAndDetachedSubscriptionAreIgnored() throws Exception {
        var work = new ArrayList<Runnable>(); var observed = new ArrayList<ServiceUpdateService.State>();
        var next = new ServiceUpdateService.State[] {new ServiceUpdateService.State(ServiceUpdateService.Status.AVAILABLE, offer(), "")};
        var service = new ServiceUpdateService(work::add, new ServiceUpdateService.Backend() {
            public ServiceUpdateService.State check() { return next[0]; }
            public String install(ApiBinaryUpdateService.UpdateOffer offer, String source, AtomicBoolean cancelled) { return ""; }
        });
        var listener = service.subscribe(observed::add);
        service.check(); service.check();
        work.remove(1).run();
        assertEquals(ServiceUpdateService.Status.AVAILABLE, service.state().status());
        listener.close(); next[0] = new ServiceUpdateService.State(ServiceUpdateService.Status.CURRENT, offer(), "");
        work.removeFirst().run();
        assertEquals(ServiceUpdateService.Status.AVAILABLE, service.state().status());
        assertEquals(3, observed.size());
    }
    @Test void duplicateInstallAndRecheckCannotPublishPartialSuccess() {
        var work = new ArrayList<Runnable>(); int[] installs = {0};
        var service = new ServiceUpdateService(work::add, new ServiceUpdateService.Backend() {
            public ServiceUpdateService.State check() { return new ServiceUpdateService.State(ServiceUpdateService.Status.AVAILABLE, offer(), ""); }
            public String install(ApiBinaryUpdateService.UpdateOffer offer, String source, AtomicBoolean cancelled) throws Exception {
                installs[0]++; assertEquals("AUTO", source); throw new java.io.IOException("verification failed");
            }
        });
        service.check(); work.removeFirst().run();
        service.download("AUTO"); service.download("AUTO"); service.check();
        assertEquals(1, work.size()); work.removeFirst().run();
        assertEquals(1, installs[0]); assertEquals(ServiceUpdateService.Status.FAILED, service.state().status());
        assertNotNull(service.state().offer());
        service.download("AUTO"); assertEquals(1, work.size());
    }
    @Test void targetPairGateRejectsBreakingUpgradeWithoutConfusingExternalOwnership() {
        String p1 = "tuneweave-http-v1", p2 = "tuneweave-http-v2";
        assertEquals(ModServiceCompatibility.Decision.MANUAL_PAIR, ModServiceCompatibility.decide(List.of(p2), Set.of(p1)));
        assertEquals(ModServiceCompatibility.Decision.COMPATIBLE, ModServiceCompatibility.decide(List.of(p1, p2), Set.of(p1)));
        assertEquals(ModServiceCompatibility.Decision.COMPATIBLE, ModServiceCompatibility.decide(List.of(p2), Set.of(p2)));
        assertEquals(ModServiceCompatibility.Decision.UNKNOWN, ModServiceCompatibility.decide(List.of(), Set.of(p1)));
        assertEquals(ModServiceCompatibility.Decision.UNKNOWN, ModServiceCompatibility.decide(List.of(p1), Set.of()));
        // A configured external server can prove its published version without being ours to restart.
        Set<String> external = ModServiceCompatibility.serviceProfiles("1.0.0-beta.1", null);
        assertEquals(Set.of(p1), external);
        assertEquals(ModServiceCompatibility.Decision.COMPATIBLE, ModServiceCompatibility.decide(List.of(p1), external));
        assertTrue(ModServiceCompatibility.serviceProfiles("1.0.0-beta.2", null).isEmpty());
    }

    @Test void serviceSelectionChangesDuringModDownloadInvalidateStaging() {
        var initial = new ModServiceCompatibility.Selection("http://127.0.0.1:7833", "managed/old", 10);
        assertDoesNotThrow(() -> ModServiceCompatibility.verifySelection(initial, initial));
        for (var changed : List.of(
                new ModServiceCompatibility.Selection("http://127.0.0.1:7834", "managed/old", 10),
                new ModServiceCompatibility.Selection(initial.url(), "managed/new", 10),
                new ModServiceCompatibility.Selection(initial.url(), initial.binary(), 11)))
            assertThrows(IllegalStateException.class, () -> ModServiceCompatibility.verifySelection(initial, changed));
        assertEquals(ModServiceCompatibility.Decision.MANUAL_PAIR,
                ModServiceCompatibility.decide(List.of("tuneweave-http-v1"), Set.of("tuneweave-http-v2")));
    }

}
