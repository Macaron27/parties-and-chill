package dev.partiesandchill.core.config;

import dev.partiesandchill.core.party.PartySettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConfigTest {

    @TempDir
    Path dir;

    @Test
    void firstStartWritesAndLoadsTheDefaults() throws Exception {
        PluginConfig config = PluginConfig.load(dir);

        assertTrue(Files.exists(dir.resolve("config.yml")));
        assertEquals(PartySettings.defaults(), config.party());
        assertFalse(config.redis().enabled());
        assertEquals("redis://localhost:6379/0", config.redis().uri());
        assertEquals("pnc:", config.redis().prefix());
        assertTrue(config.redis().proxyId().startsWith("proxy-"), "blank proxy-id gets a random one");
    }

    @Test
    void readsEditedValues() throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
                party:
                  max-size: 12
                  invite-timeout: 2m
                  disconnect-grace: 90s
                auto-warp:
                  enabled: false
                  delay: 500ms
                  servers: ["bw-*"]
                redis:
                  enabled: true
                  proxy-id: "eu-1"
                """);
        PluginConfig config = PluginConfig.load(dir);

        assertEquals(12, config.party().maxSize());
        assertEquals(Duration.ofMinutes(2), config.party().inviteTtl());
        assertEquals(Duration.ofSeconds(90), config.party().disconnectGrace());
        assertEquals(Duration.ofMillis(500), config.party().warpDelay());
        assertEquals(List.of(), config.party().gameServers(), "auto-warp disabled = no game servers");
        assertEquals(Duration.ofSeconds(30), config.party().mutedNoticeCooldown(), "missing keys use defaults");
        assertTrue(config.redis().enabled());
        assertEquals("eu-1", config.redis().proxyId());
    }

    @Test
    void invalidValuesNameTheKey() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "party:\n  invite-timeout: soon\n");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> PluginConfig.load(dir));
        assertTrue(error.getMessage().contains("party.invite-timeout"), error.getMessage());
    }
}
