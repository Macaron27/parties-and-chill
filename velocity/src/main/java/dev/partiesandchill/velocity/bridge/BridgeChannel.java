package dev.partiesandchill.velocity.bridge;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.partiesandchill.velocity.party.Party;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Outgoing side of the backend bridge, plus which backends are known to run it. */
public final class BridgeChannel {

    /** Short enough for 1.8's 20-character channel limit, namespaced for 1.13+. */
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("pnc", "main");

    private final Set<String> bridgedServers = ConcurrentHashMap.newKeySet();

    /** Remembers that {@code server} runs the bridge (it said hello). */
    public void markBridged(String server) {
        bridgedServers.add(server);
    }

    /** @return {@code true} if the player's current backend runs the bridge */
    public boolean isOnBridgedServer(Player player) {
        return player.getCurrentServer().map(s -> bridgedServers.contains(s.getServerInfo().getName())).orElse(false);
    }

    /**
     * Velocity cannot cancel signed chat (1.19.1+) without kicking the player, so for those clients the chat
     * lock has to be enforced by the backend bridge.
     *
     * @return {@code true} if chat diversion for this client must happen on the backend
     */
    public static boolean needsBackendForChatLock(Player player) {
        return player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_19_1) >= 0;
    }

    /** Pushes {@code player}'s party (or "no party" when {@code party} is null or excludes them) to their backend. */
    public void sendSnapshot(Player player, Party party) {
        BridgeMessage.Snapshot snapshot = party != null && party.isMember(player.getUniqueId())
                ? new BridgeMessage.Snapshot(player.getUniqueId(), party.leader(), List.copyOf(party.memberIds()))
                : new BridgeMessage.Snapshot(player.getUniqueId(), null, List.of());
        send(player, snapshot);
    }

    /** Tells {@code player}'s backend whether to divert their chat into party chat. */
    public void sendChatLock(Player player, boolean locked) {
        send(player, new BridgeMessage.ChatLock(player.getUniqueId(), locked));
    }

    private static void send(Player player, BridgeMessage message) {
        byte[] data = BridgeMessage.encode(message);
        player.getCurrentServer().ifPresent(server -> server.sendPluginMessage(CHANNEL, data));
    }
}
