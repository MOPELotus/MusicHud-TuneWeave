package indi.mopelotus.musichud.server;

import indi.mopelotus.musichud.network.IPlayerClient;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServerPlayerRegistryTest {
    @Test
    void joinReplacesSameUuidAndLeaveRemovesCurrentPlayer() {
        ServerPlayerRegistry registry = ServerPlayerRegistry.getInstance();
        UUID id = UUID.randomUUID();
        IPlayerClient first = player(id, "first");
        IPlayerClient replacement = player(id, "replacement");
        try {
            registry.join(first);
            assertTrue(registry.contains(id));
            assertEquals("first", registry.players().stream().filter(p -> p.getUUID().equals(id)).findFirst().orElseThrow().getName());
            registry.join(replacement);
            assertEquals(1, registry.players().stream().filter(p -> p.getUUID().equals(id)).count());
            assertEquals("replacement", registry.players().stream().filter(p -> p.getUUID().equals(id)).findFirst().orElseThrow().getName());
            registry.leave(first);
            assertTrue(registry.contains(id), "Late quit must not remove a reconnected player");
            registry.leave(replacement);
            assertFalse(registry.contains(id));
        } finally {
            registry.leave(first);
            registry.leave(replacement);
        }
    }

    private static IPlayerClient player(UUID id, String name) {
        return new IPlayerClient() {
            public UUID getUUID() { return id; }
            public String getName() { return name; }
            public ClientType getClientType() { return ClientType.REMOTE; }
        };
    }
}
