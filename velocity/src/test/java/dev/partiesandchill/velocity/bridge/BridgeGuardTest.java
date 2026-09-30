package dev.partiesandchill.velocity.bridge;

import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.partiesandchill.velocity.network.LocalNetwork;
import dev.partiesandchill.velocity.party.Party;
import dev.partiesandchill.velocity.party.PartyGuard.Action;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeGuardTest {

    static final UUID ALICE = new UUID(0, 10), BOB = new UUID(0, 11);

    final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
    final ServerConnection bw1 = server("bw-1", sent);
    final BridgeChannel bridge = new BridgeChannel();
    final LocalNetwork network = new LocalNetwork(Duration.ofMinutes(1));
    final BridgeGuard guard = new BridgeGuard(id -> Optional.of(bw1), bridge, network, LoggerFactory.getLogger("test"));
    final Party party = Party.create(ALICE, 0);

    @Test
    void backendsAreOnlyAskedAboutEventsTheirPluginsListenTo() {
        assertFalse(guard.watches(Action.JOIN, BOB), "unknown backend: no bridge");
        bridge.markBridged("bw-1", 1 << BridgeMessage.EVENT_CHAT);
        assertFalse(guard.watches(Action.JOIN, BOB));
        assertTrue(guard.allows(Action.JOIN, BOB, party, ""));
        assertTrue(guard.watches(Action.CHAT, BOB));
        assertTrue(sent.isEmpty());
    }

    @Test
    void theAskedServerDecides() throws Exception {
        bridge.markBridged("bw-1", 1 << BridgeMessage.EVENT_JOIN);
        network.playerJoined(ALICE, "Alice");
        network.serverSwitched(ALICE, "lobby");
        CompletableFuture<Boolean> allowed = CompletableFuture.supplyAsync(() -> guard.allows(Action.JOIN, BOB, party, ""));

        BridgeMessage.Check check = (BridgeMessage.Check) BridgeMessage.decode(sent.poll(5, TimeUnit.SECONDS));
        assertEquals(new BridgeMessage.Check(check.id(), BridgeMessage.EVENT_JOIN, BOB,
                new BridgeMessage.PartyInfo(party.id(), ALICE, List.of(ALICE), "lobby"), ""), check);
        guard.verdict("lobby", check.id(), true); // another backend can't answer for bw-1
        guard.verdict("bw-1", check.id() + 1, true); // nor can a stale id
        guard.verdict("bw-1", check.id(), false);
        assertFalse(allowed.get(5, TimeUnit.SECONDS));
    }

    @Test
    void silentBackendsDontBlockParties() {
        bridge.markBridged("bw-1", 1 << BridgeMessage.EVENT_DISBAND);
        long start = System.nanoTime();
        assertTrue(guard.allows(Action.DISBAND, ALICE, party, ""), "no answer = allowed");
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(waited >= BridgeGuard.TIMEOUT.toMillis() && waited < 3 * BridgeGuard.TIMEOUT.toMillis(), waited + " ms");
        assertFalse(guard.watches(Action.DISBAND, ALICE), "a silent backend isn't asked again...");
        bridge.markBridged("bw-1", 1 << BridgeMessage.EVENT_DISBAND);
        assertTrue(guard.watches(Action.DISBAND, ALICE), "...until its next hello");
    }

    @Test
    void aClosedConnectionIsAllowedRightAway() {
        ServerConnection closing = server("bw-1", null); // sendPluginMessage throws "not connected"
        BridgeGuard guard = new BridgeGuard(id -> Optional.of(closing), bridge, network, LoggerFactory.getLogger("test"));
        bridge.markBridged("bw-1", 1 << BridgeMessage.EVENT_CHAT);
        long start = System.nanoTime();
        assertTrue(guard.allows(Action.CHAT, ALICE, party, "hi"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < BridgeGuard.TIMEOUT.toMillis());
    }

    /** A backend connection that records the plugin messages sent to it ({@code sent == null}: disconnected). */
    static ServerConnection server(String name, BlockingQueue<byte[]> sent) {
        ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved(name, 25565));
        return (ServerConnection) Proxy.newProxyInstance(BridgeGuardTest.class.getClassLoader(),
                new Class<?>[]{ServerConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getServerInfo" -> info;
                    case "sendPluginMessage" -> {
                        if (sent == null) throw new IllegalStateException("Not connected to server!");
                        yield sent.add((byte[]) args[1]);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
