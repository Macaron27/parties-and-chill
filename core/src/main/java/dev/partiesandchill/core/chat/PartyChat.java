package dev.partiesandchill.core.chat;

import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.bridge.BridgeChannel;
import dev.partiesandchill.core.config.Durations;
import dev.partiesandchill.core.config.Messages;
import dev.partiesandchill.core.network.Network;
import dev.partiesandchill.core.party.PartyManager;
import dev.partiesandchill.core.party.PartyManager.Outcome;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Party chat: one-off messages ({@code /pc <message>}) and the chat lock ({@code /pc} alone), which diverts
 * everything a player types into party chat. The lock lives on the proxy the player is connected to.
 */
public final class PartyChat {

    /** Vanilla chat limit; longer lines can only come from a misbehaving backend. */
    private static final int MAX_LENGTH = 256;

    private final PartyManager manager;
    private final Network network;
    private final Messages messages;
    private final BridgeChannel bridge;
    private final Clock clock;
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();

    public PartyChat(PartyManager manager, Network network, Messages messages, BridgeChannel bridge, Clock clock) {
        this.manager = manager;
        this.network = network;
        this.messages = messages;
        this.bridge = bridge;
        this.clock = clock;
    }

    /** @return {@code true} if everything {@code player} types goes to party chat */
    public boolean isLocked(UUID player) {
        return locked.contains(player);
    }

    /** Flips the chat lock, refusing it where it can't be enforced (modern client on a backend without bridge). */
    public void toggle(ProxyPlayer player) {
        if (locked.remove(player.uniqueId())) {
            bridge.sendChatLock(player, false);
            player.sendMessage(messages.render("chat.toggled-off"));
            return;
        }
        if (manager.partyOf(player.uniqueId()).isEmpty()) {
            player.sendMessage(messages.render(Outcome.NOT_IN_PARTY.messageKey()));
            return;
        }
        if (player.signedChat() && !bridge.isOnBridgedServer(player)) {
            player.sendMessage(messages.render("chat.needs-bridge"));
            return;
        }
        locked.add(player.uniqueId());
        bridge.sendChatLock(player, true);
        player.sendMessage(messages.render("chat.toggled-on"));
    }

    /**
     * Re-checks the lock after a server switch: a modern client moving to a backend without the bridge loses
     * it (their chat would otherwise go public), everyone else gets it re-sent to the new backend.
     */
    public void serverSwitched(ProxyPlayer player) {
        if (!isLocked(player.uniqueId())) return;
        if (player.signedChat() && !bridge.isOnBridgedServer(player)) {
            locked.remove(player.uniqueId());
            player.sendMessage(messages.render("chat.lock-lost"));
        } else {
            bridge.sendChatLock(player, true);
        }
    }

    /** Drops the lock silently (player left the party or the proxy). */
    public void unlock(ProxyPlayer player) {
        if (locked.remove(player.uniqueId())) bridge.sendChatLock(player, false);
    }

    /** Forgets a player who disconnected. */
    public void forget(UUID player) {
        locked.remove(player);
    }

    /**
     * Sends a party chat line, honouring mutes.
     *
     * @param mutedUntilHint mute reported alongside the message by the backend ({@code 0} if none)
     */
    public void send(ProxyPlayer sender, String message, long mutedUntilHint) {
        String text = message.length() > MAX_LENGTH ? message.substring(0, MAX_LENGTH) : message;
        long mutedUntil = Math.max(mutedUntilHint, network.mutedUntil(sender.uniqueId()));
        Outcome outcome = manager.chat(sender.uniqueId(), text, mutedUntil);
        switch (outcome) {
            case SUCCESS, CANCELLED -> { }
            case MUTED -> sender.sendMessage(mutedUntil == Long.MAX_VALUE
                    ? messages.render("error.muted-permanent")
                    : messages.render("error.muted", Map.of("time",
                    Durations.format(Duration.ofMillis(mutedUntil - clock.millis())))));
            case NOT_IN_PARTY -> {
                unlock(sender);
                sender.sendMessage(messages.render(outcome.messageKey()));
            }
            default -> sender.sendMessage(messages.render(outcome.messageKey()));
        }
    }
}
