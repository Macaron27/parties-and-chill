package dev.partiesandchill.core.bridge;

import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.network.Network;
import dev.partiesandchill.core.party.Party;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Outgoing side of the backend bridge, plus which backends are known to run it. */
public final class BridgeChannel {

    /** Short enough for 1.8's 20-character channel limit, namespaced for 1.13+. */
    public static final String CHANNEL = "pnc:main";

    /** Backends running the bridge → events their plugins listen to ({@link BridgeMessage.Hello#listeners()}). */
    private final Map<String, Integer> bridgedServers = new ConcurrentHashMap<>();

    /** Remembers that {@code server} runs the bridge (it said hello) and which events its plugins listen to. */
    public void markBridged(String server, int listeners) {
        bridgedServers.put(server, listeners);
    }

    /** @return {@code true} if the player's current backend runs the bridge */
    public boolean isOnBridgedServer(ProxyPlayer player) {
        return player.backend().map(backend -> bridgedServers.containsKey(backend.name())).orElse(false);
    }

    /** Stops asking {@code server} about events until its next hello (it stopped answering). */
    public void stopAsking(String server) {
        bridgedServers.computeIfPresent(server, (name, listeners) -> 0);
    }

    /** @return {@code true} if plugins on {@code server} listen to {@code event} ({@code BridgeMessage.EVENT_*}) */
    public boolean listens(String server, int event) {
        return (bridgedServers.getOrDefault(server, 0) & 1 << event) != 0;
    }

    /** @return {@code party} as backend plugins see it, with the server its leader is on */
    public static BridgeMessage.PartyInfo info(Party party, Network network) {
        return new BridgeMessage.PartyInfo(party.id(), party.leader(), List.copyOf(party.memberIds()),
                network.serverOf(party.leader()).orElse(null));
    }

    /** Pushes {@code player}'s party (or "no party" when {@code party} is null or excludes them) to their backend. */
    public void sendSnapshot(ProxyPlayer player, Party party) {
        BridgeMessage.Snapshot snapshot = party != null && party.isMember(player.uniqueId())
                ? new BridgeMessage.Snapshot(player.uniqueId(), party.leader(), List.copyOf(party.memberIds()))
                : new BridgeMessage.Snapshot(player.uniqueId(), null, List.of());
        send(player, snapshot);
    }

    /** Tells {@code player}'s backend whether to divert their chat into party chat. */
    public void sendChatLock(ProxyPlayer player, boolean locked) {
        send(player, new BridgeMessage.ChatLock(player.uniqueId(), locked));
    }

    /**
     * Sends {@code message} to {@code backend}.
     *
     * @return {@code false} if the connection is gone (e.g. the player is switching servers)
     */
    public static boolean send(ProxyPlayer.Backend backend, BridgeMessage message) {
        return backend.send(BridgeMessage.encode(message));
    }

    private static void send(ProxyPlayer player, BridgeMessage message) {
        player.backend().ifPresent(backend -> send(backend, message));
    }
}
