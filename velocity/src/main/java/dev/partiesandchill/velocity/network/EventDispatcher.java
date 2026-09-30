package dev.partiesandchill.velocity.network;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.partiesandchill.velocity.bridge.BridgeChannel;
import dev.partiesandchill.velocity.bridge.BridgeMessage;
import dev.partiesandchill.velocity.chat.PartyChat;
import dev.partiesandchill.velocity.config.Messages;
import dev.partiesandchill.velocity.party.Party;
import dev.partiesandchill.velocity.party.PartyEvent;
import dev.partiesandchill.velocity.party.PartyEvent.Notice;
import dev.partiesandchill.velocity.party.PartyEvent.PartyChanged;
import dev.partiesandchill.velocity.party.PartyEvent.Warp;
import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Applies {@link PartyEvent}s to the players connected to <em>this</em> proxy. With Redis every proxy runs
 * one, so each event reaches its recipients wherever they are.
 */
public final class EventDispatcher implements Consumer<PartyEvent> {

    private final Object plugin;
    private final ProxyServer proxy;
    private final Messages messages;
    private final Network network;
    private final BridgeChannel bridge;
    private final PartyChat chat;

    public EventDispatcher(Object plugin, ProxyServer proxy, Messages messages, Network network, BridgeChannel bridge,
                           PartyChat chat) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.messages = messages;
        this.network = network;
        this.bridge = bridge;
        this.chat = chat;
    }

    @Override
    public void accept(PartyEvent event) {
        switch (event) {
            case Notice notice -> deliver(notice);
            case Warp warp -> proxy.getServer(warp.server()).ifPresent(server -> {
                if (warp.delayMillis() <= 0) connect(warp, server);
                else proxy.getScheduler().buildTask(plugin, () -> connect(warp, server))
                        .delay(warp.delayMillis(), TimeUnit.MILLISECONDS).schedule();
            });
            case PartyChanged changed -> changed.affected().forEach(id -> proxy.getPlayer(id).ifPresent(player -> {
                Party party = changed.party();
                bridge.sendSnapshot(player, party);
                if (party == null || !party.isMember(id)) {
                    chat.unlock(player);
                    if (changed.previous() != null) notifyLeft(player, changed.previous());
                }
            }));
        }
    }

    /** Fires the leave event on the player's backend, if a plugin there listens to it. */
    private void notifyLeft(Player player, Party previous) {
        player.getCurrentServer().filter(server -> bridge.listens(server, BridgeMessage.EVENT_LEAVE)).ifPresent(server ->
                BridgeChannel.send(server, new BridgeMessage.Left(player.getUniqueId(), BridgeChannel.info(previous, network))));
    }

    private void deliver(Notice notice) {
        Component message = null; // rendered lazily: most proxies host none of the recipients
        for (UUID id : notice.recipients()) {
            Player player = proxy.getPlayer(id).orElse(null);
            if (player == null) continue;
            if (message == null) message = messages.render(notice.key(), names(notice.players()), notice.values());
            player.sendMessage(message);
        }
    }

    private Map<String, String> names(Map<String, UUID> players) {
        Map<String, String> names = new HashMap<>();
        players.forEach((tag, id) -> names.put(tag, network.nameOf(id).orElse("?")));
        return names;
    }

    private void connect(Warp warp, RegisteredServer server) {
        for (UUID id : warp.members()) {
            proxy.getPlayer(id)
                    .filter(p -> p.getCurrentServer().map(s -> !s.getServer().equals(server)).orElse(true))
                    .ifPresent(p -> p.createConnectionRequest(server).fireAndForget());
        }
    }
}
