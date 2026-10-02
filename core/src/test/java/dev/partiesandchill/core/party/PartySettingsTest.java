package dev.partiesandchill.core.party;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartySettingsTest {

    @Test
    void gameServerGlobs() {
        PartySettings settings = new PartySettings(8, Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofSeconds(1),
                List.of("bw-*", "duels-?", "skywars.solo"), Duration.ofSeconds(30));
        assertTrue(settings.isGameServer("bw-12"));
        assertTrue(settings.isGameServer("BW-lobby-less"));
        assertTrue(settings.isGameServer("duels-1"));
        assertFalse(settings.isGameServer("duels-10"));
        assertTrue(settings.isGameServer("skywars.solo"));
        assertFalse(settings.isGameServer("skywarsXsolo"), "dots are literal, not regex wildcards");
        assertFalse(settings.isGameServer("lobby-1"));
    }

    @Test
    void rejectsTinyParties() {
        assertThrows(IllegalArgumentException.class, () -> new PartySettings(1, Duration.ZERO, Duration.ZERO,
                Duration.ZERO, List.of(), Duration.ZERO));
    }
}
