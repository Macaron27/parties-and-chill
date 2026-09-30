package dev.partiesandchill.bridge;

import dev.partiesandchill.api.Party;

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
    /** 2: developer API (requests, event checks, leave notices) and the listener mask in hello. */
    public static final int VERSION = 2;

    static final int SNAPSHOT = 1, CHAT_LOCK = 2, REPLY = 3, CHECK = 4, LEFT = 5,
            HELLO = 10, CHAT = 11, MUTE = 12, REQUEST = 13, VERDICT = 14;
    /** Events: {@link Incoming#code} of checks, and bits ({@code 1 << code}) of the hello listener mask. */
    public static final int EVENT_CREATE = 0, EVENT_JOIN = 1, EVENT_DISBAND = 2, EVENT_CHAT = 3, EVENT_LEAVE = 4;
    /** Developer API request actions. */
    public static final int ACTION_GET = 0, ACTION_CREATE = 1, ACTION_ADD = 2, ACTION_REMOVE = 3, ACTION_DISBAND = 4;
    /** {@link Incoming#code} of replies. */
    public static final int STATUS_FAILED = 0, STATUS_SUCCESS = 1, STATUS_ERROR = 2;
    private static final int MAX_MEMBERS = 1024;

    private BridgeProtocol() {
    }

    /** A decoded proxy → backend message; fields a type doesn't use are {@code 0}, {@code null} or empty. */
    public static final class Incoming {
        public final int type;
        public final UUID player;
        /** {@code null} when the player has no party (snapshots only). */
        public final UUID leader;
        public final List<UUID> members;
        public final boolean locked;
        /** Replies and checks: the request or check id. */
        public final int id;
        /** Replies: {@code STATUS_*}. Checks: {@code EVENT_*}. */
        public final int code;
        /** Replies (may be {@code null}), checks and leave notices. */
        public final Party party;
        /** Checks: the chat line ({@code ""} for other events). */
        public final String message;

        Incoming(int type, UUID player, UUID leader, List<UUID> members, boolean locked) {
            this(type, player, leader, members, locked, 0, 0, null, "");
        }

        Incoming(int type, int id, int code, UUID player, Party party, String message) {
            this(type, player, null, Collections.<UUID>emptyList(), false, id, code, party, message);
        }

        private Incoming(int type, UUID player, UUID leader, List<UUID> members, boolean locked, int id, int code,
                         Party party, String message) {
            this.type = type;
            this.player = player;
            this.leader = leader;
            this.members = members;
            this.locked = locked;
            this.id = id;
            this.code = code;
            this.party = party;
            this.message = message;
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
                    message = new Incoming(type, player, leader, members(in), false);
                }
            } else if (type == CHAT_LOCK) {
                message = new Incoming(type, uuid(in), null, Collections.<UUID>emptyList(), in.readBoolean());
            } else if (type == REPLY) {
                int id = in.readInt();
                int status = in.readByte();
                message = new Incoming(type, id, status, null, in.readBoolean() ? party(in) : null, "");
            } else if (type == CHECK) {
                int id = in.readInt();
                int event = in.readByte();
                UUID player = uuid(in);
                Party party = party(in);
                message = new Incoming(type, id, event, player, party, in.readUTF());
            } else if (type == LEFT) {
                UUID player = uuid(in);
                message = new Incoming(type, 0, 0, player, party(in), "");
            } else {
                throw new IllegalArgumentException("unexpected bridge message type " + type);
            }
            if (in.available() > 0) throw new IllegalArgumentException("trailing bytes in bridge message");
            return message;
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated bridge message", e);
        }
    }

    /**
     * Announces the bridge to the proxy for the connection it is sent on.
     *
     * @param listeners events plugins listen to, as {@code 1 << EVENT_*} bits: the proxy only asks about those
     */
    public static byte[] hello(final int listeners) {
        return write(HELLO, new Body() {
            public void write(DataOutputStream out) throws IOException {
                out.writeInt(VERSION);
                out.writeInt(listeners);
            }
        });
    }

    /** A developer API call, answered by a reply with the same {@code id}. */
    public static byte[] request(final int id, final int action, final UUID actor, final UUID target) {
        return write(REQUEST, new Body() {
            public void write(DataOutputStream out) throws IOException {
                out.writeInt(id);
                out.writeByte(action);
                uuid(out, actor);
                uuid(out, target);
            }
        });
    }

    /** Answers check {@code id}: {@code false} if a plugin cancelled the event. */
    public static byte[] verdict(final int id, final boolean allowed) {
        return write(VERDICT, new Body() {
            public void write(DataOutputStream out) throws IOException {
                out.writeInt(id);
                out.writeBoolean(allowed);
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

    private static Party party(DataInputStream in) throws IOException {
        UUID id = uuid(in);
        UUID leader = uuid(in);
        List<UUID> members = members(in);
        return new Party(id, leader, members, in.readBoolean() ? in.readUTF() : null);
    }

    private static List<UUID> members(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_MEMBERS) throw new IllegalArgumentException("bad member count " + count);
        List<UUID> members = new ArrayList<UUID>(count);
        for (int i = 0; i < count; i++) members.add(uuid(in));
        return Collections.unmodifiableList(members);
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
