package dev.partiesandchill.core.bridge;

import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.ProxyPlayer.Backend;
import dev.partiesandchill.core.chat.PartyChat;
import dev.partiesandchill.core.network.Network;
import dev.partiesandchill.core.party.PartyManager;
import dev.partiesandchill.core.party.PartyManager.Outcome;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Incoming side of the backend bridge. Platforms must never forward {@link BridgeChannel#CHANNEL} messages and must
 * only hand over those sent by a backend: a client could otherwise forge party data, mutes or developer API calls.
 */
public final class BridgeHandler {

    private final PartyManager manager;
    private final Network network;
    private final BridgeChannel bridge;
    private final BridgeGuard guard;
    private final PartyChat chat;
    private final Executor executor;
    private final Logger logger;

    public BridgeHandler(PartyManager manager, Network network, BridgeChannel bridge, BridgeGuard guard, PartyChat chat,
                         Executor executor, Logger logger) {
        this.manager = manager;
        this.network = network;
        this.bridge = bridge;
        this.guard = guard;
        this.chat = chat;
        this.executor = executor;
        this.logger = logger;
    }

    /**
     * Handles a message a backend sent through {@code player}'s connection.
     *
     * @param source the connection it came through; replies go back on it
     */
    public void handle(ProxyPlayer player, Backend source, byte[] data) {
        BridgeMessage message;
        try {
            message = BridgeMessage.decode(data);
        } catch (IllegalArgumentException e) {
            logger.warn("Ignoring malformed bridge message from {}: {}", source.name(), e.getMessage());
            return;
        }
        String server = source.name();
        executor.execute(() -> {
            switch (message) {
                case BridgeMessage.Hello hello -> {
                    bridge.markBridged(server, hello.listeners());
                    bridge.sendSnapshot(player, manager.partyOf(player.uniqueId()).orElse(null));
                    if (chat.isLocked(player.uniqueId())) bridge.sendChatLock(player, true);
                }
                case BridgeMessage.Chat(String text, long mutedUntil) -> chat.send(player, text, mutedUntil);
                case BridgeMessage.Mute(UUID muted, long until) -> network.setMutedUntil(muted, until);
                // Answered on the connection it came through: the requesting backend, whoever carried it.
                case BridgeMessage.Request request -> BridgeChannel.send(source, handle(request, server));
                case BridgeMessage.Verdict(int id, boolean allowed) -> guard.verdict(server, id, allowed);
                case BridgeMessage.Snapshot ignored -> backendBound(server);
                case BridgeMessage.ChatLock ignored -> backendBound(server);
                case BridgeMessage.Reply ignored -> backendBound(server);
                case BridgeMessage.Check ignored -> backendBound(server);
                case BridgeMessage.Left ignored -> backendBound(server);
            }
        });
    }

    private void backendBound(String server) {
        logger.debug("Ignoring backend-bound message sent by {}", server);
    }

    /** Runs a developer API call from a backend plugin. */
    private BridgeMessage.Reply handle(BridgeMessage.Request request, String server) {
        UUID actor = request.actor(), target = request.target();
        try {
            return switch (request.action()) {
                case BridgeMessage.ACTION_GET -> new BridgeMessage.Reply(request.id(), BridgeMessage.STATUS_SUCCESS,
                        manager.partyOf(actor).map(party -> BridgeChannel.info(party, network)).orElse(null));
                case BridgeMessage.ACTION_CREATE -> reply(request, manager.create(actor));
                case BridgeMessage.ACTION_ADD -> reply(request, manager.add(actor, target));
                case BridgeMessage.ACTION_REMOVE -> reply(request, manager.kick(actor, target));
                case BridgeMessage.ACTION_DISBAND -> reply(request, manager.disband(actor));
                default -> {
                    logger.warn("Unknown developer API action {} from {}", request.action(), server);
                    yield new BridgeMessage.Reply(request.id(), BridgeMessage.STATUS_ERROR, null);
                }
            };
        } catch (RuntimeException e) {
            logger.error("Developer API call {} from {} failed", request, server, e);
            return new BridgeMessage.Reply(request.id(), BridgeMessage.STATUS_ERROR, null);
        }
    }

    private static BridgeMessage.Reply reply(BridgeMessage.Request request, Outcome outcome) {
        int status = outcome == Outcome.SUCCESS ? BridgeMessage.STATUS_SUCCESS : BridgeMessage.STATUS_FAILED;
        return new BridgeMessage.Reply(request.id(), status, null);
    }
}
