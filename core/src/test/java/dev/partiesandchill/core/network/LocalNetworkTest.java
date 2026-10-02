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

        network.playerJoined(ALICE, "Alice");
        network.serverSwitched(ALICE, "bw-1");
        assertEquals(Optional.of("bw-1"), network.serverOf(ALICE));
        network.playerLeft(ALICE);
        assertEquals(Optional.empty(), network.serverOf(ALICE));
        network.serverSwitched(ALICE, "bw-2"); // handled after the logout
        assertEquals(Optional.empty(), network.serverOf(ALICE));
    }
}
