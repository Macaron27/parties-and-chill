package dev.partiesandchill.velocity.bridge;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.partiesandchill.velocity.chat.PartyChat;
import dev.partiesandchill.velocity.network.Network;
import dev.partiesandchill.velocity.party.PartyManager;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;

/**
 * Incoming side of the backend bridge. Messages on {@link BridgeChannel#CHANNEL} are never forwarded, and
 * only backends may send them: a client could otherwise forge party data or mutes.
 */
public final class BridgeListener {

    private final PartyManager manager;
    private final Network network;
    private final BridgeChannel bridge;
    private final PartyChat chat;
    private final ExecutorService executor;
    private final Logger logger;

    public BridgeListener(PartyManager manager, Network network, BridgeChannel bridge, PartyChat chat,
                          ExecutorService executor, Logger logger) {
        this.manager = manager;
        this.network = network;
        this.bridge = bridge;
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
        executor.execute(() -> {
            switch (message) {
                case BridgeMessage.Hello hello -> {
                    bridge.markBridged(connection.getServerInfo().getName());
                    bridge.sendSnapshot(player, manager.partyOf(player.getUniqueId()).orElse(null));
                    if (chat.isLocked(player.getUniqueId())) bridge.sendChatLock(player, true);
                }
                case BridgeMessage.Chat(String text, long mutedUntil) -> chat.send(player, text, mutedUntil);
                case BridgeMessage.Mute(java.util.UUID muted, long until) -> network.setMutedUntil(muted, until);
                case BridgeMessage.Snapshot snapshot -> logger.debug("Ignoring proxy-bound message from backend");
                case BridgeMessage.ChatLock lock -> logger.debug("Ignoring proxy-bound message from backend");
            }
        });
    }
}
