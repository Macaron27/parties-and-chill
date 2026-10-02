package dev.partiesandchill.core.bridge;

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
    /** Bridges speaking this protocol (or newer) understand {@link Sound}. */
    int PROTOCOL_SOUNDS = 3;

    /** Backend events: {@link Check#event()} codes, and {@link Hello#listeners()} bits ({@code 1 << code}). */
    int EVENT_CREATE = 0, EVENT_JOIN = 1, EVENT_DISBAND = 2, EVENT_CHAT = 3, EVENT_LEAVE = 4;
    /** {@link Request#action()} codes (developer API calls). */
    int ACTION_GET = 0, ACTION_CREATE = 1, ACTION_ADD = 2, ACTION_REMOVE = 3, ACTION_DISBAND = 4;
    /** {@link Reply#status()} codes. */
    int STATUS_FAILED = 0, STATUS_SUCCESS = 1, STATUS_ERROR = 2;

    /** A party as backend plugins see it; {@code server} is the leader's, {@code null} when they are offline. */
    record PartyInfo(UUID id, UUID leader, List<UUID> members, String server) {
        public PartyInfo {
            members = List.copyOf(members);
        }
    }

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

    /** Answer to {@link Request} {@code id}; {@code party} is only set for {@link #ACTION_GET} when there is one. */
    record Reply(int id, int status, PartyInfo party) implements BridgeMessage {
    }

    /** Asks the backend whether its plugins allow {@code event} (they may cancel it); answered by {@link Verdict}. */
    record Check(int id, int event, UUID player, PartyInfo party, String message) implements BridgeMessage {
    }

    /** {@code player} is no longer in {@code party} (shown as it was before). */
    record Left(UUID player, PartyInfo party) implements BridgeMessage {
    }

    /**
     * Since protocol 3: play {@code sound} to {@code player} (party chat mentions).
     *
     * @param sound a Bukkit {@code Sound} name or a namespaced key, resolved by the backend
     */
    record Sound(UUID player, String sound, float volume, float pitch) implements BridgeMessage {
    }

    // backend → proxy

    /**
     * Sent when a player joins a backend running the bridge.
     *
     * @param listeners since protocol 2: events backend plugins listen to, as {@code 1 << EVENT_*} bits
     */
    record Hello(int protocol, int listeners) implements BridgeMessage {
    }

    /** A developer API call; the proxy answers with {@link Reply} {@code id} on the same connection. */
    record Request(int id, int action, UUID actor, UUID target) implements BridgeMessage {
    }

    /** Answer to {@link Check} {@code id}. */
    record Verdict(int id, boolean allowed) implements BridgeMessage {
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
                case Reply(int id, int status, PartyInfo party) -> {
                    out.writeByte(3);
                    out.writeInt(id);
                    out.writeByte(status);
                    out.writeBoolean(party != null);
                    if (party != null) party(out, party);
                }
                case Check(int id, int event, UUID player, PartyInfo party, String text) -> {
                    out.writeByte(4);
                    out.writeInt(id);
                    out.writeByte(event);
                    uuid(out, player);
                    party(out, party);
                    out.writeUTF(text);
                }
                case Left(UUID player, PartyInfo party) -> {
                    out.writeByte(5);
                    uuid(out, player);
                    party(out, party);
                }
                case Sound(UUID player, String sound, float volume, float pitch) -> {
                    out.writeByte(6);
                    uuid(out, player);
                    out.writeUTF(sound);
                    out.writeFloat(volume);
                    out.writeFloat(pitch);
                }
                case Hello(int protocol, int listeners) -> {
                    out.writeByte(10);
                    out.writeInt(protocol);
                    if (protocol >= 2) out.writeInt(listeners);
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
                case Request(int id, int action, UUID actor, UUID target) -> {
                    out.writeByte(13);
                    out.writeInt(id);
                    out.writeByte(action);
                    uuid(out, actor);
                    uuid(out, target);
                }
                case Verdict(int id, boolean allowed) -> {
                    out.writeByte(14);
                    out.writeInt(id);
                    out.writeBoolean(allowed);
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
                    yield new Snapshot(player, leader, members(in));
                }
                case 2 -> new ChatLock(uuid(in), in.readBoolean());
                case 3 -> {
                    int id = in.readInt();
                    int status = in.readByte();
                    yield new Reply(id, status, in.readBoolean() ? party(in) : null);
                }
                case 4 -> new Check(in.readInt(), in.readByte(), uuid(in), party(in), in.readUTF());
                case 5 -> new Left(uuid(in), party(in));
                case 6 -> new Sound(uuid(in), in.readUTF(), in.readFloat(), in.readFloat());
                case 10 -> {
                    int protocol = in.readInt();
                    Hello hello = new Hello(protocol, protocol >= 2 ? in.readInt() : 0);
                    if (protocol > 2) in.skipBytes(in.available()); // newer bridges may append fields: keep the hello
                    yield hello;
                }
                case 11 -> new Chat(in.readUTF(), in.readLong());
                case 12 -> new Mute(uuid(in), in.readLong());
                case 13 -> new Request(in.readInt(), in.readByte(), uuid(in), uuid(in));
                case 14 -> new Verdict(in.readInt(), in.readBoolean());
                default -> throw new IllegalArgumentException("unknown bridge message type");
            };
            if (in.available() > 0) throw new IllegalArgumentException("trailing bytes in bridge message");
            return message;
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated bridge message", e);
        }
    }

    private static void party(DataOutputStream out, PartyInfo party) throws IOException {
        uuid(out, party.id());
        uuid(out, party.leader());
        out.writeInt(party.members().size());
        for (UUID member : party.members()) uuid(out, member);
        out.writeBoolean(party.server() != null);
        if (party.server() != null) out.writeUTF(party.server());
    }

    private static PartyInfo party(DataInputStream in) throws IOException {
        UUID id = uuid(in);
        UUID leader = uuid(in);
        List<UUID> members = members(in);
        return new PartyInfo(id, leader, members, in.readBoolean() ? in.readUTF() : null);
    }

    private static List<UUID> members(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_MEMBERS) throw new IllegalArgumentException("bad member count " + count);
        List<UUID> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) members.add(uuid(in));
        return members;
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
