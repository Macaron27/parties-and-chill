package dev.partiesandchill.core.network;

import dev.partiesandchill.core.ProxyPlatform;
import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.bridge.BridgeChannel;
import dev.partiesandchill.core.bridge.BridgeMessage;
import dev.partiesandchill.core.chat.PartyChat;
import dev.partiesandchill.core.config.Messages;
import dev.partiesandchill.core.party.Party;
import dev.partiesandchill.core.party.PartyEvent;
import dev.partiesandchill.core.party.PartyEvent.Notice;
import dev.partiesandchill.core.party.PartyEvent.PartyChanged;
import dev.partiesandchill.core.party.PartyEvent.Warp;
import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Applies {@link PartyEvent}s to the players connected to <em>this</em> proxy. With Redis every proxy runs
 * one, so each event reaches its recipients wherever they are.
 */
public final class EventDispatcher implements Consumer<PartyEvent> {

    private final ProxyPlatform platform;
    private final ScheduledExecutorService scheduler;
    private final Messages messages;
    private final Network network;
    private final BridgeChannel bridge;
    private final PartyChat chat;

    /** @param scheduler runs delayed warps */
    public EventDispatcher(ProxyPlatform platform, ScheduledExecutorService scheduler, Messages messages, Network network,
                           BridgeChannel bridge, PartyChat chat) {
        this.platform = platform;
        this.scheduler = scheduler;
        this.messages = messages;
        this.network = network;
        this.bridge = bridge;
        this.chat = chat;
    }

    @Override
    public void accept(PartyEvent event) {
        switch (event) {
            case Notice notice -> deliver(notice);
            case Warp warp -> {
                if (warp.delayMillis() <= 0) connect(warp);
                else scheduler.schedule(() -> connect(warp), warp.delayMillis(), TimeUnit.MILLISECONDS);
            }
            case PartyChanged changed -> changed.affected().forEach(id -> platform.player(id).ifPresent(player -> {
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
    private void notifyLeft(ProxyPlayer player, Party previous) {
        player.backend().filter(server -> bridge.listens(server.name(), BridgeMessage.EVENT_LEAVE)).ifPresent(server ->
                BridgeChannel.send(server, new BridgeMessage.Left(player.uniqueId(), BridgeChannel.info(previous, network))));
    }

    private void deliver(Notice notice) {
        Component message = null; // rendered lazily: most proxies host none of the recipients
        for (UUID id : notice.recipients()) {
            ProxyPlayer player = platform.player(id).orElse(null);
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

    private void connect(Warp warp) {
        for (UUID id : warp.members()) {
            platform.player(id)
                    .filter(p -> p.backend().map(server -> !server.name().equals(warp.server())).orElse(true))
                    .ifPresent(p -> p.connect(warp.server()));
        }
    }
}
