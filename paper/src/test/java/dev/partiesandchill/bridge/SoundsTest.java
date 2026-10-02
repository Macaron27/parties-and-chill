package dev.partiesandchill.bridge;

import org.bukkit.Sound;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Runs against the 1.8.8 API, the oldest the bridge supports: modern names must still find a sound. */
class SoundsTest {

    final List<String> warnings = new ArrayList<>();
    final Sounds sounds = new Sounds(logger());

    @Test
    void modernNamesFallBackToTheir18Names() {
        assertEquals(Sound.ORB_PICKUP, sounds.resolve("ENTITY_EXPERIENCE_ORB_PICKUP"), "the default config value");
        assertEquals(Sound.NOTE_PLING, sounds.resolve("BLOCK_NOTE_BLOCK_PLING"));
        assertEquals(Sound.LEVEL_UP, sounds.resolve("entity_player_levelup"), "case-insensitive");
        assertEquals(Sound.CLICK, sounds.resolve("CLICK"), "names this server has resolve directly");
    }

    @Test
    void unknownNamesAreLoggedOnce() {
        assertNull(sounds.resolve("NO_SUCH_SOUND"));
        assertNull(sounds.resolve("NO_SUCH_SOUND"));
        assertEquals(1, warnings.size(), warnings.toString());
    }

    private Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }
}
