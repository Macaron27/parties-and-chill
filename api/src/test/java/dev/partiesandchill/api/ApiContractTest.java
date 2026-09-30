package dev.partiesandchill.api;

import dev.partiesandchill.api.event.PartyChatEvent;
import dev.partiesandchill.api.event.PartyCreateEvent;
import dev.partiesandchill.api.event.PartyDisbandEvent;
import dev.partiesandchill.api.event.PartyEvent;
import dev.partiesandchill.api.event.PartyJoinEvent;
import dev.partiesandchill.api.event.PartyLeaveEvent;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiContractTest {

    static final UUID A = new UUID(0, 10), B = new UUID(0, 11);
    static final Party PARTY = new Party(new UUID(0, 1), A, List.of(A, B), "bw-1");

    @Test
    void everyEventIsAsyncAndHasItsOwnHandlerList() throws ReflectiveOperationException {
        List<PartyEvent> events = List.of(new PartyCreateEvent(null, PARTY), new PartyJoinEvent(null, PARTY),
                new PartyDisbandEvent(null, PARTY), new PartyChatEvent(null, PARTY, "hi"), new PartyLeaveEvent(null, PARTY));
        List<HandlerList> lists = new ArrayList<>();
        for (PartyEvent event : events) {
            assertTrue(event.isAsynchronous(), event.getEventName() + " must be async");
            assertSame(PARTY, event.getParty());
            // Bukkit finds listeners through this static method; without it registerEvents throws.
            HandlerList handlers = (HandlerList) event.getClass().getMethod("getHandlerList").invoke(null);
            assertSame(handlers, event.getHandlers());
            assertFalse(lists.contains(handlers), "handler lists must not be shared");
            lists.add(handlers);
        }
    }

    @Test
    void onlyLeaveIsNotCancellable() {
        for (PartyEvent event : List.of(new PartyCreateEvent(null, PARTY), new PartyJoinEvent(null, PARTY),
                new PartyDisbandEvent(null, PARTY), new PartyChatEvent(null, PARTY, "hi"))) {
            Cancellable cancellable = assertInstanceOf(Cancellable.class, event);
            assertFalse(cancellable.isCancelled());
            cancellable.setCancelled(true);
            assertTrue(cancellable.isCancelled());
        }
        assertFalse(Cancellable.class.isAssignableFrom(PartyLeaveEvent.class));
        assertEquals("hi", new PartyChatEvent(null, PARTY, "hi").getMessage());
    }

    @Test
    void partyIsAnImmutableSnapshot() {
        List<UUID> members = new ArrayList<>(List.of(A, B));
        Party party = new Party(PARTY.getId(), A, members, null);
        members.clear();
        assertEquals(List.of(A, B), party.getMembers());
        assertThrows(UnsupportedOperationException.class, () -> party.getMembers().add(A));
        assertEquals(null, party.getServer());
        assertEquals("bw-1", PARTY.getServer());
    }

    @Test
    void staticAccessFailsClearlyUntilRegistered() {
        NetworkParties.unregister();
        IllegalStateException error = assertThrows(IllegalStateException.class, NetworkParties::getAPI);
        assertTrue(error.getMessage().contains("depend"));
        assertThrows(NullPointerException.class, () -> NetworkParties.register(null));
        NetworkPartiesAPI api = new NetworkPartiesAPI() {
            public java.util.concurrent.CompletableFuture<java.util.Optional<Party>> getParty(UUID p) { return null; }
            public java.util.concurrent.CompletableFuture<Boolean> createParty(UUID l) { return null; }
            public java.util.concurrent.CompletableFuture<Boolean> addMember(UUID l, UUID t) { return null; }
            public java.util.concurrent.CompletableFuture<Boolean> removeMember(UUID l, UUID t) { return null; }
            public java.util.concurrent.CompletableFuture<Boolean> disbandParty(UUID l) { return null; }
            public boolean isInParty(UUID p) { return false; }
            public boolean isPartyLeader(UUID p) { return false; }
        };
        NetworkParties.register(api);
        assertNotNull(NetworkParties.getAPI());
        assertSame(api, NetworkParties.getAPI());
        NetworkParties.unregister();
    }
}
