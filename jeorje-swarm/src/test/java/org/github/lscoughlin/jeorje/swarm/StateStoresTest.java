package org.github.lscoughlin.jeorje.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateStoresTest {

    @Test
    void freshKeepsTheSavedIdWhenNoOtherInstanceUsesIt(@TempDir Path dir) {
        var saved = PeerIdStore.loadOrCreate(dir);
        assertEquals(saved, PeerIdStore.fresh(dir, Set.of("someone-else")));
        assertEquals(saved, PeerIdStore.loadOrCreate(dir));
    }

    @Test
    void freshMakesAndSavesANewIdWhenTheSavedIdIsLive(@TempDir Path dir) {
        var saved = PeerIdStore.loadOrCreate(dir);
        var next = PeerIdStore.fresh(dir, Set.of(saved));
        assertNotEquals(saved, next);
        assertEquals(next, PeerIdStore.loadOrCreate(dir));
    }

    @Test
    void aBlankTokenWritesTheFileAndLaterCallsReadIt(@TempDir Path dir) throws Exception {
        var token = SwarmTokenStore.resolve(dir, "");
        assertEquals(64, token.length());
        assertEquals(token + "\n", Files.readString(dir.resolve("swarm-token")));
        assertEquals(token, SwarmTokenStore.resolve(dir, null));
    }

    @Test
    void aConfiguredTokenWinsAndWritesNoFile(@TempDir Path dir) {
        assertEquals("mine", SwarmTokenStore.resolve(dir, "mine"));
        assertFalse(Files.exists(dir.resolve("swarm-token")));
        assertTrue(SwarmTokenStore.resolve(dir, " ").length() > 10);
    }
}
