package dev.partiesandchill.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Java 8 mirror of the proxy's {@code BridgeMessage} codec on channel {@code pnc:main}. Both sides are
 * pinned by the same golden bytes in their tests; change them together.
 */
public final class BridgeProtocol {

    /** Short enough for 1.8's 20-character limit, namespaced for 1.13+. */
    public static final String CHANNEL = "pnc:main";
    public static final int VERSION = 1;

    static final int SNAPSHOT = 1, CHAT_LOCK = 2, HELLO = 10, CHAT = 11, MUTE = 12;
    private static final int MAX_MEMBERS = 1024;

    private BridgeProtocol() {
    }

    /** A decoded proxy → backend message. */
    public static final class Incoming {
        public final int type;
        public final UUID player;
        /** {@code null} when the player has no party (snapshots only). */
        public final UUID leader;
        public final List<UUID> members;
        public final boolean locked;

        Incoming(int type, UUID player, UUID leader, List<UUID> members, boolean locked) {
            this.type = type;
            this.player = player;
            this.leader = leader;
            this.members = members;
            this.locked = locked;
        }
    }

    /** @throws IllegalArgumentException if {@code data} is not a proxy → backend message */
    public static Incoming decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int type = in.readByte();
            Incoming message;
            if (type == SNAPSHOT) {
                UUID player = uuid(in);
                if (!in.readBoolean()) {
                    message = new Incoming(type, player, null, Collections.<UUID>emptyList(), false);
                } else {
                    UUID leader = uuid(in);
                    int count = in.readInt();
                    if (count < 0 || count > MAX_MEMBERS) throw new IllegalArgumentException("bad member count " + count);
                    List<UUID> members = new ArrayList<UUID>(count);
                    for (int i = 0; i < count; i++) members.add(uuid(in));
                    message = new Incoming(type, player, leader, Collections.unmodifiableList(members), false);
                }
            } else if (type == CHAT_LOCK) {
                message = new Incoming(type, uuid(in), null, Collections.<UUID>emptyList(), in.readBoolean());
            } else {
                throw new IllegalArgumentException("unexpected bridge message type " + type);
            }
            if (in.available() > 0) throw new IllegalArgumentException("trailing bytes in bridge message");
            return message;
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated bridge message", e);
        }
    }

    /** Announces the bridge to the proxy for the connection it is sent on. */
    public static byte[] hello() {
        return write(HELLO, new Body() {
            public void write(DataOutputStream out) throws IOException {
                out.writeInt(VERSION);
            }
        });
    }

    /**
     * @param mutedUntil epoch millis the sender's mute ends ({@code 0}: none, {@link Long#MAX_VALUE}: permanent)
     */
    public static byte[] chat(final String message, final long mutedUntil) {
        return write(CHAT, new Body() {
            public void write(DataOutputStream out) throws IOException {
                out.writeUTF(message);
                out.writeLong(mutedUntil);
            }
        });
    }

    /** @param until epoch millis the mute ends, {@code 0} when lifted */
    public static byte[] mute(final UUID player, final long until) {
        return write(MUTE, new Body() {
            public void write(DataOutputStream out) throws IOException {
                uuid(out, player);
                out.writeLong(until);
            }
        });
    }

    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] write(int type, Body body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(type);
            body.write(out);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e); // in-memory stream: cannot happen
        }
        return bytes.toByteArray();
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
