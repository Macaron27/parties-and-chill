package dev.partiesandchill.velocity.redis;

import dev.partiesandchill.velocity.party.Invite;
import dev.partiesandchill.velocity.party.Party;
import dev.partiesandchill.velocity.party.PartyEvent;
import dev.partiesandchill.velocity.party.PartyMember;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.RedisClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisNetworkTest {

    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    RedisClient redis;
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final List<RedisNetwork> networks = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        redis = LocalRedis.client();
    }

    @AfterEach
    void tearDown() {
        networks.forEach(RedisNetwork::close);
        executor.shutdownNow();
    }

    RedisNetwork proxy(String id) {
        RedisNetwork network = new RedisNetwork(redis, "net:", id, Duration.ofMinutes(20), executor,
                LoggerFactory.getLogger("test"));
        networks.add(network);
        return network;
    }

    @Test
    void presenceIsSharedAndOnlyTheOwningProxyCanClearIt() {
        RedisNetwork a = proxy("a"), b = proxy("b");
        a.start(e -> { });
        b.start(e -> { });
        a.playerJoined(ALICE, "Alice");

        assertTrue(b.isOnline(ALICE));
        assertEquals(Optional.of(ALICE), b.findOnline("ALICE"));
        assertEquals(Optional.of("Alice"), b.nameOf(ALICE));

        UUID bob = UUID.randomUUID();
        b.playerJoined(bob, "Bob");
        assertTrue(a.isOnline(bob), "b started after a's last refresh: falls back to its heartbeat key");

        b.playerLeft(ALICE); // stale disconnect from a proxy the player already left
        assertTrue(a.isOnline(ALICE));
        a.playerLeft(ALICE);
        assertFalse(b.isOnline(ALICE));
        assertEquals(Optional.of(ALICE), b.uuidOf("alice"), "name stays resolvable for the grace period");
    }

    @Test
    void playersOfADeadProxyEnterTheGracePeriod() {
        redis.sadd("net:proxies", "ghost");
        redis.hset("net:online", ALICE.toString(), "ghost"); // registered, but "ghost" never heart-beats
        redis.hset("net:server", ALICE.toString(), "bw-1");
        RedisNetwork b = proxy("b");
        BlockingQueue<UUID> lost = new ArrayBlockingQueue<>(4);
        b.onPlayersLost(lost::add);
        b.start(e -> { });

        assertFalse(b.isOnline(ALICE), "no heartbeat = not online");
        b.refreshProxies();
        assertEquals(ALICE, lost.poll());
        assertEquals(Optional.empty(), b.serverOf(ALICE), "reaping clears the server too");
        assertTrue(lost.isEmpty());
        assertFalse(redis.sismember("net:proxies", "ghost"));
    }

    @Test
    void restartingUnderTheSameIdReleasesItsOldPlayers() {
        redis.hset("net:online", ALICE.toString(), "eu-1");
        RedisNetwork restarted = proxy("eu-1");
        BlockingQueue<UUID> lost = new ArrayBlockingQueue<>(4);
        restarted.onPlayersLost(lost::add);
        restarted.start(e -> { });
        assertEquals(ALICE, lost.poll());
    }

    @Test
    void mutesExpireAndCanBeLifted() {
        RedisNetwork a = proxy("a");
        long inAnHour = System.currentTimeMillis() + 3_600_000;
        a.setMutedUntil(ALICE, inAnHour);
        assertEquals(inAnHour, a.mutedUntil(ALICE));
        assertTrue(redis.pttl("net:mute:" + ALICE) > 0, "key expires with the mute");
        a.setMutedUntil(ALICE, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, a.mutedUntil(ALICE));
        a.setMutedUntil(ALICE, 0);
        assertEquals(0, a.mutedUntil(ALICE));
    }

    @Test
    void eventsReachEveryProxy() throws InterruptedException {
        BlockingQueue<PartyEvent> atA = new ArrayBlockingQueue<>(512), atB = new ArrayBlockingQueue<>(512);
        RedisNetwork a = proxy("a"), b = proxy("b");
        a.start(atA::add);
        b.start(atB::add);
        awaitSubscribers(2);

        PartyEvent event = new PartyEvent.Notice(Set.of(ALICE), "chat.format", Map.of("player", ALICE),
                Map.of("message", "<b>hi \"there\""));
        a.publish(event);
        assertEquals(event, nextRealEvent(atB));
        assertEquals(event, nextRealEvent(atA), "the publishing proxy delivers too");
    }

    @Test
    void jsonRoundTripsEveryShape() {
        Party party = new Party(UUID.randomUUID(), ALICE,
                List.of(PartyMember.joined(ALICE, 1), new PartyMember(UUID.randomUUID(), 2, 99)),
                List.of(new Invite(UUID.randomUUID(), ALICE, 60_000)));
        assertEquals(party, Json.party(Json.party(party)));
        for (PartyEvent event : List.of(
                new PartyEvent.Warp(Set.of(ALICE), "bw-1", 1000),
                new PartyEvent.PartyChanged(Set.of(ALICE), party, null),
                new PartyEvent.PartyChanged(Set.of(ALICE), null, party))) {
            assertEquals(event, Json.event(Json.event(event)));
        }
    }

    @Test
    void partyChangedStaysCompatibleAcrossProxyVersions() {
        // From a 1.0 proxy: no "previous".
        assertEquals(new PartyEvent.PartyChanged(Set.of(ALICE), null, null),
                Json.event("{\"type\":\"PartyChanged\",\"data\":{\"affected\":[\"" + ALICE + "\"],\"party\":null}}"));
        // What 1.0 proxies rely on to read ours: fields they don't know are ignored.
        assertEquals(new PartyEvent.PartyChanged(Set.of(), null, null),
                Json.event("{\"type\":\"PartyChanged\",\"data\":{\"affected\":[],\"party\":null,\"future\":1}}"));
    }

    @Test
    void serversAreTrackedByTheOwningProxyOnly() {
        RedisNetwork a = proxy("a"), b = proxy("b");
        a.start(e -> { });
        b.start(e -> { });
        a.playerJoined(ALICE, "Alice");
        a.serverSwitched(ALICE, "bw-1");
        assertEquals(Optional.of("bw-1"), b.serverOf(ALICE));

        b.serverSwitched(ALICE, "lobby"); // stale: ALICE isn't b's player
        assertEquals(Optional.of("bw-1"), a.serverOf(ALICE));
        a.playerLeft(ALICE);
        assertEquals(Optional.empty(), b.serverOf(ALICE));
        a.serverSwitched(ALICE, "bw-2"); // handled after the logout
        assertEquals(Optional.empty(), a.serverOf(ALICE));
    }

    @Test
    void lockExcludesOtherProxies() throws InterruptedException {
        RedisPartyStore first = new RedisPartyStore(redis, "lock-test:"), second = new RedisPartyStore(redis, "lock-test:");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 200; i++) {
            RedisPartyStore store = i % 2 == 0 ? first : second;
            pool.execute(() -> store.atomically(() -> { // unsafe read-modify-write, safe only under the lock
                int value = Integer.parseInt(Optional.ofNullable(redis.get("counter")).orElse("0"));
                return redis.set("counter", Integer.toString(value + 1));
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals("200", redis.get("counter"));
    }

    /** Skips the subscription probes still in flight. */
    private static PartyEvent nextRealEvent(BlockingQueue<PartyEvent> queue) throws InterruptedException {
        while (true) {
            PartyEvent event = queue.poll(5, TimeUnit.SECONDS);
            if (!(event instanceof PartyEvent.Warp warp && warp.server().equals("probe"))) return event;
        }
    }

    private void awaitSubscribers(int count) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            // PUBLISH returns how many subscribers got the probe
            String probe = Json.event(new PartyEvent.Warp(Set.of(), "probe", 0));
            if (redis.publish("net:events", probe) >= count) return;
            Thread.sleep(20);
        }
        throw new AssertionError("subscribers never connected");
    }
}
