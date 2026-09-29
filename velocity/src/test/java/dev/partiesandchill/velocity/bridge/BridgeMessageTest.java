package dev.partiesandchill.velocity.bridge;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Golden bytes shared with paper-bridge's BridgeProtocolTest: change both together. */
class BridgeMessageTest {

    static final UUID A = new UUID(0, 10), B = new UUID(0, 11);
    static final String SNAPSHOT = "010000000000000000000000000000000a010000000000000000000000000000000a00000002"
            + "0000000000000000000000000000000a0000000000000000000000000000000b";
    static final String NO_PARTY = "010000000000000000000000000000000b00";
    static final String CHAT_LOCK = "020000000000000000000000000000000a01";
    static final String HELLO = "0a00000001";
    static final String CHAT = "0b000768c3a9203c623e0000000000000005";
    static final String MUTE = "0c0000000000000000000000000000000b7fffffffffffffff";

    @Test
    void proxyToBackendMessagesMatchTheGoldenBytes() {
        assertEquals(SNAPSHOT, hex(new BridgeMessage.Snapshot(A, A, List.of(A, B))));
        assertEquals(NO_PARTY, hex(new BridgeMessage.Snapshot(B, null, List.of())));
        assertEquals(CHAT_LOCK, hex(new BridgeMessage.ChatLock(A, true)));
    }

    @Test
    void backendToProxyGoldenBytesDecode() {
        assertEquals(new BridgeMessage.Hello(1), decode(HELLO));
        assertEquals(new BridgeMessage.Chat("hé <b>", 5), decode(CHAT));
        assertEquals(new BridgeMessage.Mute(B, Long.MAX_VALUE), decode(MUTE));
    }

    @Test
    void everyMessageRoundTrips() {
        for (BridgeMessage message : List.of(new BridgeMessage.Snapshot(A, B, List.of(B, A)),
                new BridgeMessage.Snapshot(A, null, List.of()), new BridgeMessage.ChatLock(B, false),
                new BridgeMessage.Hello(7), new BridgeMessage.Chat("", 0), new BridgeMessage.Mute(A, 0))) {
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
