package dev.partiesandchill.core.network;

import dev.partiesandchill.core.ProxyPlatform;
import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.bridge.BridgeChannel;
import dev.partiesandchill.core.bridge.BridgeMessage;
import dev.partiesandchill.core.chat.PartyChat;
import dev.partiesandchill.core.config.Messages;
import dev.partiesandchill.core.config.PluginConfig;
import dev.partiesandchill.core.party.Party;
import dev.partiesandchill.core.party.PartyEvent;
import dev.partiesandchill.core.party.PartyEvent.Chat;
import dev.partiesandchill.core.party.PartyEvent.Notice;
import dev.partiesandchill.core.party.PartyEvent.PartyChanged;
import dev.partiesandchill.core.party.PartyEvent.Warp;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
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
    private final PluginConfig.Chat chatConfig;

    /** @param scheduler runs delayed warps */
    public EventDispatcher(ProxyPlatform platform, ScheduledExecutorService scheduler, Messages messages, Network network,
                           BridgeChannel bridge, PartyChat chat, PluginConfig.Chat chatConfig) {
        this.platform = platform;
        this.scheduler = scheduler;
        this.messages = messages;
        this.network = network;
        this.bridge = bridge;
        this.chat = chat;
        this.chatConfig = chatConfig;
    }

    @Override
    public void accept(PartyEvent event) {
        switch (event) {
            case Notice notice -> deliver(notice);
            case Chat line -> deliver(line);
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

    /** Renders the line once for this proxy's members, once for its spies, and pings the members it mentions. */
    private void deliver(Chat line) {
        Component rendered = null, spied = null, actionBar = null;
        for (UUID id : line.recipients()) {
            ProxyPlayer player = platform.player(id).orElse(null);
            if (player == null) continue;
            if (rendered == null) rendered = render(line, "chat.format");
            player.sendMessage(rendered);
            if (!line.mentioned().contains(id)) continue;
            if (actionBar == null) actionBar = messages.render("chat.mention-actionbar", names(Map.of("player", line.sender())), Map.of());
            player.sendActionBar(actionBar);
            if (!chatConfig.mentionSound().isEmpty()) {
                bridge.sendSound(player, chatConfig.mentionSound(), chatConfig.volume(), chatConfig.pitch());
            }
        }
        for (UUID id : chat.spies()) { // usually empty: no lookups unless staff are spying here
            if (line.recipients().contains(id)) continue;
            ProxyPlayer spy = platform.player(id).orElse(null);
            if (spy == null) continue;
            if (!spy.hasPermission(PartyChat.SPY_PERMISSION)) { // revoked since they turned it on
                chat.spies().remove(id);
                continue;
            }
            if (spied == null) spied = render(line, "chat.spy");
            spy.sendMessage(spied);
        }
    }

    private Component render(Chat line, String key) {
        Map<String, String> players = names(Map.of("player", line.sender(), "owner", line.owner()));
        Map<String, String> values = Map.of("time", chatConfig.timestamps().format(Instant.ofEpochMilli(line.sentAt())));
        return messages.render(key, players, values, Map.of("message", highlighted(line)));
    }

    /** @return the raw text (never parsed) with each {@code @name} of a mentioned member styled as {@code chat.mention} */
    private Component highlighted(Chat line) {
        String text = line.message();
        if (line.mentioned().isEmpty()) return Component.text(text);
        Map<String, String> mentioned = new HashMap<>();
        for (UUID id : line.mentioned()) network.nameOf(id).ifPresent(name -> mentioned.put(name.toLowerCase(Locale.ROOT), name));
        TextComponent.Builder out = Component.text();
        Matcher matcher = PartyChat.MENTION.matcher(text);
        int plainFrom = 0;
        while (matcher.find()) {
            String name = mentioned.get(matcher.group(1).toLowerCase(Locale.ROOT));
            if (name == null) continue;
            out.append(Component.text(text.substring(plainFrom, matcher.start())));
            out.append(messages.render("chat.mention", Map.of("player", name), Map.of()));
            plainFrom = matcher.end();
        }
        return out.append(Component.text(text.substring(plainFrom))).build();
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
