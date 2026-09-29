package dev.partiesandchill.bridge;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Party data the proxy pushed for players on this server. Registered in Bukkit's {@code ServicesManager},
 * so other backend plugins can read parties too:
 * <pre>{@code PartyCache parties = Bukkit.getServicesManager().load(PartyCache.class);}</pre>
 * Read-only: parties are changed on the proxy ({@code /party}).
 */
public final class PartyCache {

    /** A player's party as seen by the proxy. */
    public static final class PartyView {
        private final UUID leader;
        private final List<UUID> members;

        PartyView(UUID leader, List<UUID> members) {
            this.leader = leader;
            this.members = members;
        }

        /** @return the party leader */
        public UUID leader() {
            return leader;
        }

        /** @return every member in join order, leader included, on any server of the network */
        public List<UUID> members() {
            return members;
        }
    }

    private final Map<UUID, PartyView> parties = new ConcurrentHashMap<UUID, PartyView>();
    private final Set<UUID> chatLocked = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    /** @return the party of {@code player}, or {@code null} if they have none (or are not on this server) */
    public PartyView partyOf(UUID player) {
        return parties.get(player);
    }

    /** @return {@code true} if {@code player}'s chat must be diverted into party chat */
    public boolean isChatLocked(UUID player) {
        return chatLocked.contains(player);
    }

    void apply(BridgeProtocol.Incoming message) {
        if (message.type == BridgeProtocol.SNAPSHOT) {
            if (message.leader == null) parties.remove(message.player);
            else parties.put(message.player, new PartyView(message.leader, message.members));
        } else if (message.type == BridgeProtocol.CHAT_LOCK) {
            if (message.locked) chatLocked.add(message.player); else chatLocked.remove(message.player);
        }
    }

    void forget(UUID player) {
        parties.remove(player);
        chatLocked.remove(player);
    }
}
