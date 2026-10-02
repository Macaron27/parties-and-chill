package dev.partiesandchill.bridge;

import dev.partiesandchill.api.Party;
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

    static final UUID A = new UUID(0, 10), B = new UUID(0, 11), PARTY_ID = new UUID(0, 1);
    public static final String SNAPSHOT = "010000000000000000000000000000000a010000000000000000000000000000000a00000002"
            + "0000000000000000000000000000000a0000000000000000000000000000000b";
    static final String NO_PARTY = "010000000000000000000000000000000b00";
    static final String CHAT_LOCK = "020000000000000000000000000000000a01";
    static final String HELLO_V2 = "0a000000020000001f";
    static final String CHAT = "0b000768c3a9203c623e0000000000000005";
    static final String MUTE = "0c0000000000000000000000000000000b7fffffffffffffff";
    // protocol 2: developer API
    /** PartyInfo(PARTY_ID, A, [A, B], "bw-1") */
    static final String INFO = "00000000000000000000000000000001" + "0000000000000000000000000000000a" + "00000002"
            + "0000000000000000000000000000000a" + "0000000000000000000000000000000b" + "01000462772d31";
    public static final String REPLY = "03" + "00000007" + "01" + "01" + INFO;
    public static final String REPLY_EMPTY = "03000000080000";
    static final String CHECK = "04" + "00000009" + "03" + "0000000000000000000000000000000b" + INFO + "000368c3a9";
    static final String LEFT = "05" + "0000000000000000000000000000000b" + "00000000000000000000000000000001"
            + "0000000000000000000000000000000a" + "00000001" + "0000000000000000000000000000000a" + "00";
    static final String REQUEST = "0d" + "00000005" + "02" + "0000000000000000000000000000000a"
            + "0000000000000000000000000000000b";
    static final String VERDICT = "0e0000000900";
    // protocol 3: sounds
    /** Sound(A, "ORB", 0.5, 2.0) */
    static final String SOUND = "06" + "0000000000000000000000000000000a" + "00034f5242" + "3f000000" + "40000000";
    static final String HELLO_V3 = "0a000000030000001f";

    @Test
    void backendToProxyMessagesMatchTheGoldenBytes() {
        assertEquals(HELLO_V3, hex(BridgeProtocol.hello(0x1f)));
        assertEquals(REQUEST, hex(BridgeProtocol.request(5, BridgeProtocol.ACTION_ADD, A, B)));
        assertEquals(VERDICT, hex(BridgeProtocol.verdict(9, false)));
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
    void developerApiGoldenBytesDecode() {
        BridgeProtocol.Incoming reply = BridgeProtocol.decode(bytes(REPLY));
        assertEquals(7, reply.id);
        assertEquals(BridgeProtocol.STATUS_SUCCESS, reply.code);
        assertParty(reply.party, List.of(A, B), "bw-1");
        BridgeProtocol.Incoming empty = BridgeProtocol.decode(bytes(REPLY_EMPTY));
        assertEquals(8, empty.id);
        assertEquals(BridgeProtocol.STATUS_FAILED, empty.code);
        assertNull(empty.party);

        BridgeProtocol.Incoming check = BridgeProtocol.decode(bytes(CHECK));
        assertEquals(9, check.id);
        assertEquals(BridgeProtocol.EVENT_CHAT, check.code);
        assertEquals(B, check.player);
        assertEquals("hé", check.message);
        assertParty(check.party, List.of(A, B), "bw-1");

        BridgeProtocol.Incoming left = BridgeProtocol.decode(bytes(LEFT));
        assertEquals(BridgeProtocol.LEFT, left.type);
        assertEquals(B, left.player);
        assertParty(left.party, List.of(A), null);
    }

    @Test
    void soundGoldenBytesDecode() {
        BridgeProtocol.Incoming sound = BridgeProtocol.decode(bytes(SOUND));
        assertEquals(BridgeProtocol.SOUND, sound.type);
        assertEquals(A, sound.player);
        assertEquals("ORB", sound.message);
        assertEquals(0.5f, sound.volume);
        assertEquals(2f, sound.pitch);
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(bytes(SOUND.substring(0, SOUND.length() - 2))));
    }

    @Test
    void rejectsGarbageAndBackendBoundTypes() {
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(bytes(HELLO_V2)), "not proxy → backend");
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(bytes(VERDICT)), "not proxy → backend");
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(bytes(REPLY + "00")));
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(bytes(CHECK.substring(0, 40))));
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(bytes(CHAT_LOCK + "00")));
    }

    private static void assertParty(Party party, List<UUID> members, String server) {
        assertEquals(PARTY_ID, party.getId());
        assertEquals(A, party.getLeader());
        assertEquals(members, party.getMembers());
        assertEquals(server, party.getServer());
    }

    public static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
