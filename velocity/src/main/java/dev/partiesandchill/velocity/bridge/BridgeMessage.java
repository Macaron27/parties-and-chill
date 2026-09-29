package dev.partiesandchill.velocity.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plugin messages exchanged with the backend bridge on channel {@code pnc:main}. The wire format is a type
 * byte followed by {@link DataOutputStream} fields; the Java 8 bridge has a mirror codec, and both sides
 * are pinned by the same golden bytes in their tests.
 */
public sealed interface BridgeMessage {

    int MAX_MEMBERS = 1024;

    // proxy → backend

    /** The party of {@code player}: {@code leader == null} means "not in a party". */
    record Snapshot(UUID player, UUID leader, List<UUID> members) implements BridgeMessage {
        public Snapshot {
            members = List.copyOf(members);
        }
    }

    /** Whether the backend must divert {@code player}'s chat into party chat. */
    record ChatLock(UUID player, boolean locked) implements BridgeMessage {
    }

    // backend → proxy

    /** Sent when a player joins a backend running the bridge. */
    record Hello(int protocol) implements BridgeMessage {
    }

    /** A chat line the backend diverted (sender = the connection it came through). */
    record Chat(String message, long mutedUntil) implements BridgeMessage {
    }

    /** A mute change reported by AdvancedBan on the backend ({@code until == 0}: unmuted). */
    record Mute(UUID player, long until) implements BridgeMessage {
    }

    /** @return the wire form of {@code message} */
    static byte[] encode(BridgeMessage message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            switch (message) {
                case Snapshot(UUID player, UUID leader, List<UUID> members) -> {
                    out.writeByte(1);
                    uuid(out, player);
                    out.writeBoolean(leader != null);
                    if (leader != null) {
                        uuid(out, leader);
                        out.writeInt(members.size());
                        for (UUID member : members) uuid(out, member);
                    }
                }
                case ChatLock(UUID player, boolean locked) -> {
                    out.writeByte(2);
                    uuid(out, player);
                    out.writeBoolean(locked);
                }
                case Hello(int protocol) -> {
                    out.writeByte(10);
                    out.writeInt(protocol);
                }
                case Chat(String text, long mutedUntil) -> {
                    out.writeByte(11);
                    out.writeUTF(text);
                    out.writeLong(mutedUntil);
                }
                case Mute(UUID player, long until) -> {
                    out.writeByte(12);
                    uuid(out, player);
                    out.writeLong(until);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // in-memory stream: cannot happen
        }
        return bytes.toByteArray();
    }

    /**
     * @return the decoded message
     * @throws IllegalArgumentException if {@code data} is not a valid bridge message
     */
    static BridgeMessage decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            BridgeMessage message = switch (in.readByte()) {
                case 1 -> {
                    UUID player = uuid(in);
                    if (!in.readBoolean()) yield new Snapshot(player, null, List.of());
                    UUID leader = uuid(in);
                    int count = in.readInt();
                    if (count < 0 || count > MAX_MEMBERS) throw new IllegalArgumentException("bad member count " + count);
                    List<UUID> members = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) members.add(uuid(in));
                    yield new Snapshot(player, leader, members);
                }
                case 2 -> new ChatLock(uuid(in), in.readBoolean());
                case 10 -> new Hello(in.readInt());
                case 11 -> new Chat(in.readUTF(), in.readLong());
                case 12 -> new Mute(uuid(in), in.readLong());
                default -> throw new IllegalArgumentException("unknown bridge message type");
            };
            if (in.available() > 0) throw new IllegalArgumentException("trailing bytes in bridge message");
            return message;
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated bridge message", e);
        }
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
