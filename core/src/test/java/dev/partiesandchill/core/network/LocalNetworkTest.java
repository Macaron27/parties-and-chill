package dev.partiesandchill.core.network;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalNetworkTest {

    static final UUID ALICE = new UUID(0, 10);

    @Test
    void serverIsKnownOnlyWhileOnline() {
        LocalNetwork network = new LocalNetwork(Duration.ofMinutes(1));
        network.serverSwitched(ALICE, "lobby"); // not logged in yet
        assertEquals(Optional.empty(), network.serverOf(ALICE));

        network.playerJoined(ALICE, "Alice", 8);
        network.serverSwitched(ALICE, "bw-1");
        assertEquals(Optional.of("bw-1"), network.serverOf(ALICE));
        network.playerLeft(ALICE);
        assertEquals(Optional.empty(), network.serverOf(ALICE));
        assertEquals(8, network.sizeLimit(ALICE), "kept while they may come back within the grace period");
        assertEquals(0, network.sizeLimit(new UUID(0, 11)));
        network.serverSwitched(ALICE, "bw-2"); // handled after the logout
        assertEquals(Optional.empty(), network.serverOf(ALICE));
    }

    @Test
    void sizeLimitsLastAsLongAsTheSessionThenTheRetention() throws InterruptedException {
        LocalNetwork network = new LocalNetwork(Duration.ofMillis(1));
        network.playerJoined(ALICE, "Alice", 24);
        Thread.sleep(20); // longer than the retention: an online player's limit must not expire
        assertEquals(24, network.sizeLimit(ALICE));
        network.playerLeft(ALICE);
        Thread.sleep(20);
        assertEquals(0, network.sizeLimit(ALICE), "forgotten once the retention after leaving is over");
    }
}
