package indi.mopelotus.musichud.server;

import indi.mopelotus.musichud.beans.music.PusherInfo;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleSourceAccessPolicyTest {
    @Test
    void privateSourceIsVisibleOnlyToItsMinecraftOwner() {
        UUID ownerId = UUID.randomUUID();
        PusherInfo owner = new PusherInfo(ownerId, "owner");

        assertTrue(IdleSourceAccessPolicy.canViewPrivateSource(owner, ownerId));
        assertFalse(IdleSourceAccessPolicy.canViewPrivateSource(owner, UUID.randomUUID()));
        assertFalse(IdleSourceAccessPolicy.canViewPrivateSource(null, ownerId));
        assertFalse(IdleSourceAccessPolicy.canViewPrivateSource(owner, null));
    }
}
