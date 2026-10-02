package dev.partiesandchill.core;

import dev.partiesandchill.core.bridge.BridgeMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** The wiring every platform relies on: platform events and commands in, messages, snapshots and warps out. */
class PartiesCoreTest {

    @TempDir
    Path dataDirectory;

    final FakePlatform platform = new FakePlatform();
    final FakePlayer alice = platform.join("Alice", "lobby"), bob = platform.join("Bob", "lobby");
    PartiesCore core;

    @BeforeEach
    void start() throws Exception {
        core = PartiesCore.start(platform, dataDirectory, LoggerFactory.getLogger("test")).get(10, TimeUnit.SECONDS);
        for (FakePlayer player : List.of(alice, bob)) {
            core.playerJoined(player);
            core.serverSwitched(player); // proxies fire it for the first server too
            core.bridgeMessage(player, player.backend, hello(0));
        }
        // a snapshot after the switch, another after the hello
        await(() -> bob.backend.received.size() == 2 && alice.backend.received.size() == 2);
        alice.backend.received.clear();
        bob.backend.received.clear();
    }

    @AfterEach
    void stop() {
        core.close();
    }

    @Test
    void writesTheDefaultConfigOnFirstStart() {
        assertTrue(Files.exists(dataDirectory.resolve("config.yml")));
        assertTrue(Files.exists(dataDirectory.resolve("messages.yml")));
    }

    @Test
    void invitesAndAcceptsThroughTheCommands() throws Exception {
        party();
        await(() -> bob.saw("You joined Alice's party!"));
        BridgeMessage.Snapshot snapshot = bob.backend.next(BridgeMessage.Snapshot.class);
        assertEquals(alice.id, snapshot.leader());
        assertEquals(List.of(alice.id, bob.id), snapshot.members());
    }

    @Test
    void unknownNamesAreReported() {
        core.partyCommand().execute(alice, new String[]{"Nobody"});
        await(() -> alice.saw("Nobody"));
    }

    @Test
    void lockedChatIsDivertedOnlyForUnsignedClients() {
        party();
        core.chatCommand().execute(alice, new String[0]);
        await(() -> alice.saw("Party chat locked on."));

        assertTrue(core.chat(alice, "gg"), "the proxy cancels it");
        await(() -> bob.saw("Party » Alice: gg"));
        assertFalse(core.chat(bob, "hello"), "not locked");

        alice.signed = true;
        assertFalse(core.chat(alice, "secret"), "signed chat is left to the backend bridge");
    }

    @Test
    void membersFollowTheLeaderIntoGames() {
        party();
        alice.backend = new FakeBackend("bw-1"); // a game server in the default config
        core.serverSwitched(alice);
        await(() -> bob.saw("Following Alice to bw-1..."));
        await(() -> bob.connects.contains("bw-1")); // after the 1 s warp delay
        assertTrue(alice.connects.isEmpty(), "the leader is already there");
    }

    @Test
    void answersDeveloperApiCallsOnTheConnectionTheyCameThrough() throws Exception {
        party();
        bob.backend.received.clear();
        FakeBackend requester = new FakeBackend("lobby");
        core.bridgeMessage(bob, requester, BridgeMessage.encode(new BridgeMessage.Request(7, BridgeMessage.ACTION_GET, bob.id, bob.id)));

        BridgeMessage.Reply reply = requester.next(BridgeMessage.Reply.class);
        assertEquals(7, reply.id());
        assertEquals(BridgeMessage.STATUS_SUCCESS, reply.status());
        assertNotNull(reply.party());
        assertEquals(alice.id, reply.party().leader());
        assertEquals("lobby", reply.party().server());
    }

    @Test
    void ignoresMalformedBridgeMessages() {
        core.bridgeMessage(bob, bob.backend, new byte[]{99});
        core.bridgeMessage(bob, bob.backend, BridgeMessage.encode(new BridgeMessage.Request(1, BridgeMessage.ACTION_GET, bob.id, bob.id)));
        await(() -> !bob.backend.received.isEmpty()); // still answering
    }

    @Test
    void disconnectedMembersKeepTheirSlot() {
        party();
        platform.players.remove(bob.id);
        core.playerLeft(bob.id);
        await(() -> alice.saw("Bob disconnected. They have 5m to rejoin before being removed."));
    }

    /** Alice invites Bob, Bob accepts. */
    private void party() {
        core.partyCommand().execute(alice, new String[]{"Bob"});
        await(() -> bob.saw("Alice invited you to their party!"));
        core.partyCommand().execute(bob, new String[]{"accept", "Alice"});
        await(() -> alice.saw("Bob joined the party."));
    }

    private static byte[] hello(int listeners) {
        return BridgeMessage.encode(new BridgeMessage.Hello(2, listeners));
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out");
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    static final class FakePlatform implements ProxyPlatform {
        final Map<UUID, FakePlayer> players = new ConcurrentHashMap<>();

        FakePlayer join(String name, String server) {
            FakePlayer player = new FakePlayer(UUID.nameUUIDFromBytes(name.getBytes()), name, new FakeBackend(server));
            players.put(player.id, player);
            return player;
        }

        @Override
        public Optional<ProxyPlayer> player(UUID id) {
            return Optional.ofNullable(players.get(id));
        }

        @Override
        public List<String> playerNames() {
            return players.values().stream().map(p -> p.name).toList();
        }
    }

    static final class FakePlayer implements ProxyPlayer {
        final UUID id;
        final String name;
        final List<String> messages = new CopyOnWriteArrayList<>();
        final List<String> connects = new CopyOnWriteArrayList<>();
        volatile FakeBackend backend;
        volatile boolean signed;

        FakePlayer(UUID id, String name, FakeBackend backend) {
            this.id = id;
            this.name = name;
            this.backend = backend;
        }

        boolean saw(String text) {
            return messages.stream().anyMatch(message -> message.contains(text));
        }

        @Override
        public UUID uniqueId() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void sendMessage(Component message) {
            messages.add(PlainTextComponentSerializer.plainText().serialize(message));
        }

        @Override
        public Optional<Backend> backend() {
            return Optional.ofNullable(backend);
        }

        @Override
        public boolean signedChat() {
            return signed;
        }

        @Override
        public void connect(String server) {
            connects.add(server);
        }
    }

    static final class FakeBackend implements ProxyPlayer.Backend {
        final String name;
        final BlockingQueue<BridgeMessage> received = new LinkedBlockingQueue<>();

        FakeBackend(String name) {
            this.name = name;
        }

        /** @return the next message of {@code type}, skipping others */
        <T extends BridgeMessage> T next(Class<T> type) throws InterruptedException {
            while (true) {
                BridgeMessage message = received.poll(5, TimeUnit.SECONDS);
                if (message == null) fail("no " + type.getSimpleName());
                if (type.isInstance(message)) return assertInstanceOf(type, message);
            }
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean send(byte[] message) {
            return received.add(BridgeMessage.decode(message));
        }
    }
}
