package dev.partiesandchill.bridge;

import dev.partiesandchill.api.Party;
import dev.partiesandchill.api.event.PartyChatEvent;
import dev.partiesandchill.api.event.PartyCreateEvent;
import dev.partiesandchill.api.event.PartyDisbandEvent;
import dev.partiesandchill.api.event.PartyJoinEvent;
import dev.partiesandchill.api.event.PartyLeaveEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeApiTest {

    static final UUID A = BridgeProtocolTest.A, B = BridgeProtocolTest.B;

    final FakeTransport transport = new FakeTransport();
    final BridgeApi api = new BridgeApi(TestAccess.cacheWith(BridgeProtocolTest.SNAPSHOT), transport);

    @Test
    void syncChecksReadTheLocalCache() {
        assertTrue(api.isInParty(A));
        assertTrue(api.isPartyLeader(A));
        assertFalse(api.isInParty(B), "only players the proxy pushed to this server are known");
        assertFalse(api.isPartyLeader(B));
        assertTrue(transport.sent.isEmpty(), "no round trip");
    }

    @Test
    void getPartyAsksTheProxy() throws Exception {
        CompletableFuture<Optional<Party>> party = api.getParty(B);
        assertEquals(hex(BridgeProtocol.request(transport.id(0), BridgeProtocol.ACTION_GET, B, B)), transport.sentHex(0));
        assertEquals(List.of(B, B), transport.carriers.getFirst(), "the player asked about carries it if online here");

        api.complete(reply(transport.id(0), BridgeProtocolTest.REPLY));
        Party found = party.get().orElseThrow();
        assertEquals(List.of(A, B), found.getMembers());
        assertEquals("bw-1", found.getServer());

        CompletableFuture<Optional<Party>> none = api.getParty(A);
        api.complete(reply(transport.id(1), BridgeProtocolTest.REPLY_EMPTY));
        assertEquals(Optional.empty(), none.get());
    }

    @Test
    void changesReportWhetherTheProxyAppliedThem() throws Exception {
        CompletableFuture<Boolean> add = api.addMember(A, B);
        CompletableFuture<Boolean> kick = api.removeMember(A, B);
        CompletableFuture<Boolean> create = api.createParty(A);
        CompletableFuture<Boolean> disband = api.disbandParty(A);
        assertEquals(hex(BridgeProtocol.request(transport.id(0), BridgeProtocol.ACTION_ADD, A, B)), transport.sentHex(0));
        assertEquals(List.of(B, A), transport.carriers.get(0), "the joining player carries it: the proxy asks their backend");
        assertEquals(hex(BridgeProtocol.request(transport.id(1), BridgeProtocol.ACTION_REMOVE, A, B)), transport.sentHex(1));
        assertEquals(List.of(A, B), transport.carriers.get(1), "otherwise the leader does");
        assertEquals(hex(BridgeProtocol.request(transport.id(2), BridgeProtocol.ACTION_CREATE, A, A)), transport.sentHex(2));
        assertEquals(hex(BridgeProtocol.request(transport.id(3), BridgeProtocol.ACTION_DISBAND, A, A)), transport.sentHex(3));
        assertEquals(transport.id(0) + 3, transport.id(3), "one id per request");

        api.complete(status(transport.id(0), BridgeProtocol.STATUS_SUCCESS));
        api.complete(status(transport.id(1), BridgeProtocol.STATUS_FAILED));
        api.complete(status(transport.id(2), BridgeProtocol.STATUS_ERROR));
        assertTrue(add.get());
        assertFalse(kick.get());
        assertFailsWith(IllegalStateException.class, create);
        api.close();
        assertFailsWith(CancellationException.class, disband); // disabling the plugin ends what's still pending
    }

    @Test
    void failuresCompleteExceptionally() {
        transport.online = false;
        assertFailsWith(IllegalStateException.class, api.createParty(A));

        transport.online = true;
        CompletableFuture<Boolean> unanswered = api.createParty(A);
        assertEquals(BridgeApi.TIMEOUT_TICKS, transport.timeouts.getFirst().ticks);
        transport.timeouts.forEach(timeout -> timeout.task.run());
        assertFailsWith(TimeoutException.class, unanswered);
        api.complete(status(transport.id(0), BridgeProtocol.STATUS_SUCCESS)); // the late answer is dropped

        assertThrows(NullPointerException.class, () -> api.addMember(A, null));
    }

    @Test
    void helloAdvertisesTheEventsPluginsListenTo() {
        assertEquals(0, BridgePlugin.listenedEvents());
        RegisteredListener chat = listener(), leave = listener();
        PartyChatEvent.getHandlerList().register(chat);
        PartyLeaveEvent.getHandlerList().register(leave);
        try {
            assertEquals(1 << BridgeProtocol.EVENT_CHAT | 1 << BridgeProtocol.EVENT_LEAVE, BridgePlugin.listenedEvents());
        } finally {
            PartyChatEvent.getHandlerList().unregister(chat);
            PartyLeaveEvent.getHandlerList().unregister(leave);
        }
    }

    @Test
    void checksBecomeTheMatchingCancellableEvent() {
        String check = BridgeProtocolTest.CHECK; // EVENT_CHAT, "hé"
        PartyChatEvent chat = assertInstanceOf(PartyChatEvent.class, BridgePlugin.checkEvent(null, decode(check)));
        assertEquals("hé", chat.getMessage());
        assertEquals(List.of(A, B), chat.getParty().getMembers());
        assertInstanceOf(PartyCreateEvent.class, BridgePlugin.checkEvent(null, decode(withEvent(check, BridgeProtocol.EVENT_CREATE))));
        assertInstanceOf(PartyJoinEvent.class, BridgePlugin.checkEvent(null, decode(withEvent(check, BridgeProtocol.EVENT_JOIN))));
        assertInstanceOf(PartyDisbandEvent.class, BridgePlugin.checkEvent(null, decode(withEvent(check, BridgeProtocol.EVENT_DISBAND))));
        assertNull(BridgePlugin.checkEvent(null, decode(withEvent(check, 42))), "unknown events are allowed, not fired");
    }

    private static BridgeProtocol.Incoming reply(int id, String goldenReply) {
        return decode("03" + String.format("%08x", id) + goldenReply.substring(10));
    }

    private static BridgeProtocol.Incoming status(int id, int status) {
        return decode("03" + String.format("%08x%02x00", id, status));
    }

    private static String withEvent(String check, int event) {
        return check.substring(0, 10) + String.format("%02x", event) + check.substring(12);
    }

    private static BridgeProtocol.Incoming decode(String hex) {
        return BridgeProtocol.decode(BridgeProtocolTest.bytes(hex));
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static RegisteredListener listener() {
        return new RegisteredListener(new Listener() { }, (l, e) -> { }, EventPriority.NORMAL, null, false);
    }

    private static void assertFailsWith(Class<? extends Throwable> type, CompletableFuture<?> future) {
        ExecutionException error = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(type, error.getCause());
    }

    /** Runs "sync" tasks inline, keeps timeouts for the test to fire, records what is sent. */
    static final class FakeTransport implements BridgeApi.Transport {
        record Later(long ticks, Runnable task) {
        }

        final List<byte[]> sent = new ArrayList<>();
        final List<List<UUID>> carriers = new ArrayList<>();
        final List<Later> timeouts = new ArrayList<>();
        boolean online = true;

        @Override
        public void sync(Runnable task) {
            task.run();
        }

        @Override
        public void later(long ticks, Runnable task) {
            timeouts.add(new Later(ticks, task));
        }

        @Override
        public boolean send(byte[] message, UUID... carriers) {
            if (!online) return false;
            sent.add(message);
            this.carriers.add(List.of(carriers));
            return true;
        }

        String sentHex(int index) {
            return hex(sent.get(index));
        }

        /** @return the request id of the {@code index}th message sent (after the type byte) */
        int id(int index) {
            return java.nio.ByteBuffer.wrap(sent.get(index), 1, 4).getInt();
        }
    }
}
