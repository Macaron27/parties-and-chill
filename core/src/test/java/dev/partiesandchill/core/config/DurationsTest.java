package dev.partiesandchill.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationsTest {

    @Test
    void parsesConfigStyleDurations() {
        assertEquals(Duration.ofSeconds(60), Durations.parse("60s"));
        assertEquals(Duration.ofSeconds(60), Durations.parse("60"));
        assertEquals(Duration.ofMinutes(5), Durations.parse("5m"));
        assertEquals(Duration.ofSeconds(90), Durations.parse("1m 30s"));
        assertEquals(Duration.ofMillis(1500), Durations.parse("1s500ms"));
        assertEquals(Duration.ofHours(2), Durations.parse(" 2H "));
    }

    @Test
    void rejectsGarbage() {
        for (String bad : new String[]{"", "abc", "5 minutes", "10x", "-5s", "5m trailing"}) {
            assertThrows(IllegalArgumentException.class, () -> Durations.parse(bad), bad);
        }
    }

    @Test
    void formatsCompactly() {
        assertEquals("5m", Durations.format(Duration.ofMinutes(5)));
        assertEquals("1m30s", Durations.format(Duration.ofSeconds(90)));
        assertEquals("1d2h", Durations.format(Duration.ofHours(26)));
        assertEquals("0s", Durations.format(Duration.ofMillis(400)));
    }
}
