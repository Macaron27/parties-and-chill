package dev.partiesandchill.bridge;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Golden bytes shared with the proxy's BridgeMessageTest: change both together. */
public class BridgeProtocolTest {

    static final UUID A = new UUID(0, 10), B = new UUID(0, 11);
    public static final String SNAPSHOT = "010000000000000000000000000000000a010000000000000000000000000000000a00000002"
            + "0000000000000000000000000000000a0000000000000000000000000000000b";
    static final String NO_PARTY = "010000000000000000000000000000000b00";
    static final String CHAT_LOCK = "020000000000000000000000000000000a01";
    static final String HELLO = "0a00000001";
    static final String CHAT = "0b000768c3a9203c623e0000000000000005";
    static final String MUTE = "0c0000000000000000000000000000000b7fffffffffffffff";

    @Test
    void backendToProxyMessagesMatchTheGoldenBytes() {
        assertEquals(HELLO, hex(BridgeProtocol.hello()));
        assertEquals(CHAT, hex(BridgeProtocol.chat("hé <b>", 5)));
        assertEquals(MUTE, hex(BridgeProtocol.mute(B, Long.MAX_VALUE)));
    }

    @Test
    void proxyGoldenBytesFeedTheCache() {
        PartyCache cache = new PartyCache();
        cache.apply(BridgeProtocol.decode(bytes(SNAPSHOT)));
        cache.apply(BridgeProtocol.decode(bytes(CHAT_LOCK)));

        assertEquals(A, cache.partyOf(A).leader());
        assertEquals(List.of(A, B), cache.partyOf(A).members());
        assertTrue(cache.isChatLocked(A));

        cache.apply(BridgeProtocol.decode(bytes(NO_PARTY)));
        assertNull(cache.partyOf(B));
        cache.forget(A);
        assertNull(cache.partyOf(A));
        assertFalse(cache.isChatLocked(A));
    }

    @Test
    void rejectsGarbageAndBackendBoundTypes() {
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(bytes(HELLO)), "not proxy → backend");
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(bytes(CHAT_LOCK + "00")));
    }

    public static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
