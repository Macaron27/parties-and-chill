package dev.partiesandchill.core.redis;

import dev.partiesandchill.core.PartiesCore;
import dev.partiesandchill.core.ProxyPlatform;
import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.party.Party;
import dev.partiesandchill.core.party.PartyMember;
import dev.partiesandchill.core.party.PartySettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.RedisClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RedisStartupTest {

    static final UUID ALICE = new UUID(0, 1), BOB = new UUID(0, 2);

    @TempDir
    Path dataDirectory;

    /** start() reaps the players a crashed run left behind; they must enter the grace period, not stay "online". */
    @Test
    void playersLostInACrashEnterTheGracePeriod() throws Exception {
        RedisClient redis = LocalRedis.client();
        RedisPartyStore store = new RedisPartyStore(redis, "pnc:");
        long now = System.currentTimeMillis();
        Party party = Party.create(ALICE, now, PartySettings.DEFAULTS).withMember(PartyMember.joined(BOB, now));
        store.atomically(() -> {
            store.save(party);
            return null;
        });
        redis.sadd("pnc:proxies", "eu-1");
        redis.hset("pnc:online", ALICE.toString(), "eu-1"); // the crashed run never logged Alice out
        Files.writeString(dataDirectory.resolve("config.yml"),
                "redis:\n  enabled: true\n  uri: \"" + LocalRedis.uri() + "\"\n  proxy-id: eu-1\n");

        ProxyPlatform nobody = new ProxyPlatform() {
            @Override
            public Optional<ProxyPlayer> player(UUID id) {
                return Optional.empty();
            }

            @Override
            public List<String> playerNames() {
                return List.of();
            }
        };
        PartiesCore core = PartiesCore.start(nobody, dataDirectory, LoggerFactory.getLogger("test")).get(10, TimeUnit.SECONDS);
        try {
            PartyMember alice = store.byMember(ALICE).orElseThrow().member(ALICE).orElseThrow();
            assertFalse(alice.online(), "flagged disconnected, so she is dropped once the grace period ends");
        } finally {
            core.close();
        }
    }
}
