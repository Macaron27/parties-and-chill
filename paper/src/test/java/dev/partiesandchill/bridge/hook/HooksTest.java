package dev.partiesandchill.bridge.hook;

import dev.partiesandchill.bridge.BridgeProtocolTest;
import dev.partiesandchill.bridge.PartyCache;
import dev.partiesandchill.bridge.TestAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HooksTest {

    static final UUID A = new UUID(0, 10), B = new UUID(0, 11), C = new UUID(0, 12);

    @Test
    void advancedBanUuidsWithOrWithoutDashes() {
        assertEquals(A, AdvancedBanHook.parseUuid("0000000000000000000000000000000a"));
        assertEquals(A, AdvancedBanHook.parseUuid(A.toString()));
        assertNull(AdvancedBanHook.parseUuid("127.0.0.1"), "IP punishments are not players");
        assertNull(AdvancedBanHook.parseUuid(null));
    }

    @Test
    void bwProxyAdapterReadsProxyPartiesAndIgnoresMutations() {
        PartyCache cache = TestAccess.cacheWith(BridgeProtocolTest.SNAPSHOT);
        BwProxy2023Adapter adapter = new BwProxy2023Adapter(cache);

        assertTrue(adapter.hasParty(A));
        assertTrue(adapter.isOwner(A));
        assertEquals(2, adapter.partySize(A));
        assertEquals(List.of(A, B), adapter.getMembers(A));
        assertEquals(A, adapter.getOwner(A));
        assertTrue(adapter.isMember(A, B));
        assertFalse(adapter.hasParty(C));
        assertFalse(adapter.isInternal(), "BWProxy must not run its own party messages");

        adapter.disband(A);
        adapter.removeFromParty(A);
        adapter.removePlayer(A, B);
        assertEquals(List.of(A, B), adapter.getMembers(A), "only the proxy changes parties");
    }
}
