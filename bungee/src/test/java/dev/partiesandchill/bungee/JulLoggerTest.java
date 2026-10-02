package dev.partiesandchill.bungee;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class JulLoggerTest {

    @Test
    void formatsPlaceholdersAndKeepsTheException() {
        List<LogRecord> records = new ArrayList<>();
        Logger jul = Logger.getAnonymousLogger();
        jul.setUseParentHandlers(false);
        jul.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        JulLogger logger = new JulLogger(jul);
        IllegalStateException failure = new IllegalStateException("boom");

        logger.info("Proxy {} ready ({})", "eu-1", "Redis");
        logger.warn("Developer API call {} from {} failed", "get", "bw-1", failure);
        logger.debug("dropped below INFO");

        assertEquals(2, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
        assertEquals("Proxy eu-1 ready (Redis)", records.get(0).getMessage());
        assertNull(records.get(0).getThrown());
        assertEquals(Level.WARNING, records.get(1).getLevel());
        assertEquals("Developer API call get from bw-1 failed", records.get(1).getMessage());
        assertSame(failure, records.get(1).getThrown());
    }
}
