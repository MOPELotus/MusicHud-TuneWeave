package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.Version;
import indi.mopelotus.musichud.ProjectIdentity;

import java.util.Set;

public final class ProtocolInfo {
    public static final String PROJECT_ID = ProjectIdentity.PROJECT_ID;
    public static final Set<ProtocolCapability> CAPABILITIES = Set.of(
            ProtocolCapability.PUBLIC_PLAYBACK_SESSION,
            ProtocolCapability.RESOURCE_QUALITY_METADATA,
            ProtocolCapability.CLIENT_IDLE_SOURCE_SNAPSHOT,
            ProtocolCapability.IDLE_SOURCE_PLAY_MODES,
            ProtocolCapability.PLAYBACK_SOURCE_CONTEXT,
            ProtocolCapability.FRAGMENTED_PAYLOADS,
            ProtocolCapability.RESOLVED_TRACK_IDENTITY);

    private ProtocolInfo() {
    }

    public static boolean isCompatible(String projectId, Version version,
                                       Set<ProtocolCapability> capabilities) {
        return PROJECT_ID.equals(projectId)
                && Version.compatibleWith(version)
                && capabilities != null
                && capabilities.containsAll(CAPABILITIES);
    }
}
