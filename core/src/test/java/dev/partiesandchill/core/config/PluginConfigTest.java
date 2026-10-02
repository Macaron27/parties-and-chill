package dev.partiesandchill.core.config;

import dev.partiesandchill.core.party.PartyRules;
import dev.partiesandchill.core.party.PartyRules.Right;
import dev.partiesandchill.core.party.PartyRules.SizePermission;
import dev.partiesandchill.core.party.PartySettings;
import dev.partiesandchill.core.party.PartySettings.Toggle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

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
        assertEquals(PartyRules.defaults(), config.party());
        assertEquals("ENTITY_EXPERIENCE_ORB_PICKUP", config.chat().mentionSound());
        assertEquals(1f, config.chat().volume());
        assertEquals(1f, config.chat().pitch());
        assertEquals(8, config.party().sizeLimit(permission -> false));
        assertEquals(24, config.party().sizeLimit(Set.of("parties.size.12", "parties.size.24")::contains));
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
    void readsRolesSizesDefaultsAndChat() throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
                party:
                  max-size: 5
                  permission-sizes:
                    ranks.gold: 10
                    ranks.diamond: 20
                  defaults:
                    public: true
                    all-warp: true
                roles:
                  moderator:
                    kick: false
                    settings: true
                chat:
                  timestamp-format: "dd/MM HH:mm"
                  mention:
                    sound: "minecraft:block.note_block.pling"
                    volume: 0.5
                    pitch: 2
                """);
        PluginConfig config = PluginConfig.load(dir);

        assertEquals(List.of(new SizePermission("ranks.diamond", 20), new SizePermission("ranks.gold", 10)),
                config.party().sizePermissions(), "dotted keys stay whole, biggest first");
        assertEquals(Set.of(Right.INVITE, Right.WARP, Right.SETTINGS, Right.START_GAMES, Right.MODERATE_CHAT),
                config.party().moderatorRights(), "listed keys override, missing ones keep their default");
        assertEquals(PartySettings.DEFAULTS.with(Toggle.PUBLIC, true).with(Toggle.ALL_WARP, true), config.party().partyDefaults());
        assertEquals("minecraft:block.note_block.pling", config.chat().mentionSound());
        assertEquals(0.5f, config.chat().volume());
        assertEquals(2f, config.chat().pitch());
        String stamp = config.chat().timestamps().format(Instant.EPOCH);
        assertTrue(stamp.matches("\\d\\d/\\d\\d \\d\\d:\\d\\d"), stamp);
    }

    @Test
    void invalidValuesNameTheKey() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "party:\n  invite-timeout: soon\n");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> PluginConfig.load(dir));
        assertTrue(error.getMessage().contains("party.invite-timeout"), error.getMessage());
    }

    @Test
    void invalidSizesAndPatternsNameTheKey() throws Exception {
        for (String yaml : List.of(
                "party:\n  permission-sizes:\n    parties.size.huge: 5000\n",
                "party:\n  permission-sizes:\n    parties.size.many: lots\n",
                "party:\n  max-size: 1\n",
                "chat:\n  timestamp-format: \"HH:mm{\"\n")) {
            Files.writeString(dir.resolve("config.yml"), yaml);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> PluginConfig.load(dir), yaml);
            String expected = yaml.startsWith("chat") ? "chat.timestamp-format"
                    : yaml.contains("max-size") ? "party.max-size" : "party.permission-sizes.parties.size.";
            assertTrue(error.getMessage().startsWith("config.yml: " + expected), error.getMessage());
        }
    }
}
