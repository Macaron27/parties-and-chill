package dev.partiesandchill.velocity.bridge;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.partiesandchill.velocity.network.Network;
import dev.partiesandchill.velocity.party.Party;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Outgoing side of the backend bridge, plus which backends are known to run it. */
public final class BridgeChannel {

    /** Short enough for 1.8's 20-character channel limit, namespaced for 1.13+. */
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("pnc", "main");

    /** Backends running the bridge → events their plugins listen to ({@link BridgeMessage.Hello#listeners()}). */
    private final Map<String, Integer> bridgedServers = new ConcurrentHashMap<>();

    /** Remembers that {@code server} runs the bridge (it said hello) and which events its plugins listen to. */
    public void markBridged(String server, int listeners) {
        bridgedServers.put(server, listeners);
    }

    /** @return {@code true} if the player's current backend runs the bridge */
    public boolean isOnBridgedServer(Player player) {
        return player.getCurrentServer().map(s -> bridgedServers.containsKey(s.getServerInfo().getName())).orElse(false);
    }

    /** Stops asking {@code server} about events until its next hello (it stopped answering). */
    public void stopAsking(String server) {
        bridgedServers.computeIfPresent(server, (name, listeners) -> 0);
    }

    /** @return {@code true} if plugins on {@code server} listen to {@code event} ({@code BridgeMessage.EVENT_*}) */
    public boolean listens(ServerConnection server, int event) {
        return (bridgedServers.getOrDefault(server.getServerInfo().getName(), 0) & 1 << event) != 0;
    }

    /** @return {@code party} as backend plugins see it, with the server its leader is on */
    public static BridgeMessage.PartyInfo info(Party party, Network network) {
        return new BridgeMessage.PartyInfo(party.id(), party.leader(), List.copyOf(party.memberIds()),
                network.serverOf(party.leader()).orElse(null));
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

    /**
     * Sends {@code message} to the backend behind {@code server}.
     *
     * @return {@code false} if the connection is gone (e.g. the player is switching servers)
     */
    public static boolean send(ServerConnection server, BridgeMessage message) {
        try {
            return server.sendPluginMessage(CHANNEL, BridgeMessage.encode(message));
        } catch (IllegalStateException e) { // "not connected"
            return false;
        }
    }

    private static void send(Player player, BridgeMessage message) {
        player.getCurrentServer().ifPresent(server -> send(server, message));
    }
}
