package dev.partiesandchill.core.bridge;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Golden bytes shared with the paper module's BridgeProtocolTest: change both together. */
class BridgeMessageTest {

    static final UUID A = new UUID(0, 10), B = new UUID(0, 11), PARTY_ID = new UUID(0, 1);
    static final String SNAPSHOT = "010000000000000000000000000000000a010000000000000000000000000000000a00000002"
            + "0000000000000000000000000000000a0000000000000000000000000000000b";
    static final String NO_PARTY = "010000000000000000000000000000000b00";
    static final String CHAT_LOCK = "020000000000000000000000000000000a01";
    static final String HELLO = "0a00000001";
    static final String CHAT = "0b000768c3a9203c623e0000000000000005";
    static final String MUTE = "0c0000000000000000000000000000000b7fffffffffffffff";
    // protocol 2: developer API
    /** PartyInfo(PARTY_ID, A, [A, B], "bw-1") */
    static final String INFO = "00000000000000000000000000000001" + "0000000000000000000000000000000a" + "00000002"
            + "0000000000000000000000000000000a" + "0000000000000000000000000000000b" + "01000462772d31";
    static final String REPLY = "03" + "00000007" + "01" + "01" + INFO;
    static final String REPLY_EMPTY = "03000000080000";
    static final String CHECK = "04" + "00000009" + "03" + "0000000000000000000000000000000b" + INFO + "000368c3a9";
    static final String LEFT = "05" + "0000000000000000000000000000000b" + "00000000000000000000000000000001"
            + "0000000000000000000000000000000a" + "00000001" + "0000000000000000000000000000000a" + "00";
    static final String HELLO_V2 = "0a000000020000001f";
    static final String REQUEST = "0d" + "00000005" + "02" + "0000000000000000000000000000000a"
            + "0000000000000000000000000000000b";
    static final String VERDICT = "0e0000000900";

    static final BridgeMessage.PartyInfo PARTY = new BridgeMessage.PartyInfo(PARTY_ID, A, List.of(A, B), "bw-1");

    @Test
    void proxyToBackendMessagesMatchTheGoldenBytes() {
        assertEquals(SNAPSHOT, hex(new BridgeMessage.Snapshot(A, A, List.of(A, B))));
        assertEquals(NO_PARTY, hex(new BridgeMessage.Snapshot(B, null, List.of())));
        assertEquals(CHAT_LOCK, hex(new BridgeMessage.ChatLock(A, true)));
        assertEquals(REPLY, hex(new BridgeMessage.Reply(7, BridgeMessage.STATUS_SUCCESS, PARTY)));
        assertEquals(REPLY_EMPTY, hex(new BridgeMessage.Reply(8, BridgeMessage.STATUS_FAILED, null)));
        assertEquals(CHECK, hex(new BridgeMessage.Check(9, BridgeMessage.EVENT_CHAT, B, PARTY, "hé")));
        assertEquals(LEFT, hex(new BridgeMessage.Left(B, new BridgeMessage.PartyInfo(PARTY_ID, A, List.of(A), null))));
    }

    @Test
    void backendToProxyGoldenBytesDecode() {
        assertEquals(new BridgeMessage.Hello(1, 0), decode(HELLO), "protocol 1 bridges still say hello");
        assertEquals(new BridgeMessage.Hello(2, 0x1f), decode(HELLO_V2));
        assertEquals(new BridgeMessage.Hello(3, 0x1f), BridgeMessage.decode(HexFormat.of().parseHex("0a000000030000001f" + "cafe")),
                "fields appended by newer bridges don't cost us the hello");
        assertEquals(new BridgeMessage.Request(5, BridgeMessage.ACTION_ADD, A, B), decode(REQUEST));
        assertEquals(new BridgeMessage.Verdict(9, false), decode(VERDICT));
        assertEquals(new BridgeMessage.Chat("hé <b>", 5), decode(CHAT));
        assertEquals(new BridgeMessage.Mute(B, Long.MAX_VALUE), decode(MUTE));
    }

    @Test
    void everyMessageRoundTrips() {
        for (BridgeMessage message : List.of(new BridgeMessage.Snapshot(A, B, List.of(B, A)),
                new BridgeMessage.Snapshot(A, null, List.of()), new BridgeMessage.ChatLock(B, false),
                new BridgeMessage.Hello(7, 3), new BridgeMessage.Chat("", 0), new BridgeMessage.Mute(A, 0),
                new BridgeMessage.Reply(1, BridgeMessage.STATUS_ERROR, null), new BridgeMessage.Check(2, 0, A, PARTY, ""),
                new BridgeMessage.Left(A, PARTY), new BridgeMessage.Request(3, 4, A, A), new BridgeMessage.Verdict(4, true))) {
            assertEquals(message, BridgeMessage.decode(BridgeMessage.encode(message)));
        }
    }

    @Test
    void rejectsGarbage() {
        byte[] hello = HexFormat.of().parseHex(HELLO);
        assertThrows(IllegalArgumentException.class, () -> BridgeMessage.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> BridgeMessage.decode(new byte[]{99}));
        assertThrows(IllegalArgumentException.class, () -> BridgeMessage.decode(java.util.Arrays.copyOf(hello, 3)));
        assertThrows(IllegalArgumentException.class, () -> BridgeMessage.decode(java.util.Arrays.copyOf(hello, 6)));
        byte[] hugeCount = HexFormat.of().parseHex(SNAPSHOT.substring(0, 68) + "7fffffff");
        assertThrows(IllegalArgumentException.class, () -> BridgeMessage.decode(hugeCount));
    }

    private static String hex(BridgeMessage message) {
        return HexFormat.of().formatHex(BridgeMessage.encode(message));
    }

    private static BridgeMessage decode(String hex) {
        byte[] bytes = HexFormat.of().parseHex(hex);
        assertArrayEquals(bytes, BridgeMessage.encode(BridgeMessage.decode(bytes)));
        return BridgeMessage.decode(bytes);
    }
}
