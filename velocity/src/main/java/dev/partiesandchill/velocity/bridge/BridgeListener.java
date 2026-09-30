package dev.partiesandchill.velocity.bridge;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.partiesandchill.velocity.chat.PartyChat;
import dev.partiesandchill.velocity.network.Network;
import dev.partiesandchill.velocity.party.PartyManager;
import dev.partiesandchill.velocity.party.PartyManager.Outcome;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Incoming side of the backend bridge. Messages on {@link BridgeChannel#CHANNEL} are never forwarded, and
 * only backends may send them: a client could otherwise forge party data, mutes or developer API calls.
 */
public final class BridgeListener {

    private final PartyManager manager;
    private final Network network;
    private final BridgeChannel bridge;
    private final BridgeGuard guard;
    private final PartyChat chat;
    private final ExecutorService executor;
    private final Logger logger;

    public BridgeListener(PartyManager manager, Network network, BridgeChannel bridge, BridgeGuard guard, PartyChat chat,
                          ExecutorService executor, Logger logger) {
        this.manager = manager;
        this.network = network;
        this.bridge = bridge;
        this.guard = guard;
        this.chat = chat;
        this.executor = executor;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(BridgeChannel.CHANNEL)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) return;

        BridgeMessage message;
        try {
            message = BridgeMessage.decode(event.getData());
        } catch (IllegalArgumentException e) {
            logger.warn("Ignoring malformed bridge message from {}: {}", connection.getServerInfo().getName(), e.getMessage());
            return;
        }
        Player player = connection.getPlayer();
        String server = connection.getServerInfo().getName();
        executor.execute(() -> {
            switch (message) {
                case BridgeMessage.Hello hello -> {
                    bridge.markBridged(server, hello.listeners());
                    bridge.sendSnapshot(player, manager.partyOf(player.getUniqueId()).orElse(null));
                    if (chat.isLocked(player.getUniqueId())) bridge.sendChatLock(player, true);
                }
                case BridgeMessage.Chat(String text, long mutedUntil) -> chat.send(player, text, mutedUntil);
                case BridgeMessage.Mute(UUID muted, long until) -> network.setMutedUntil(muted, until);
                // Answered on the connection it came through: the requesting backend, whoever carried it.
                case BridgeMessage.Request request -> BridgeChannel.send(connection, handle(request, server));
                case BridgeMessage.Verdict(int id, boolean allowed) -> guard.verdict(server, id, allowed);
                case BridgeMessage.Snapshot _, BridgeMessage.ChatLock _, BridgeMessage.Reply _, BridgeMessage.Check _,
                     BridgeMessage.Left _ -> logger.debug("Ignoring backend-bound message sent by {}", server);
            }
        });
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
