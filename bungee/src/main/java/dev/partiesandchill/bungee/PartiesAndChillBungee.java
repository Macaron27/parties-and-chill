package dev.partiesandchill.bungee;

import dev.partiesandchill.core.PartiesCore;
import dev.partiesandchill.core.ProxyPlatform;
import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.bridge.BridgeChannel;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/** BungeeCord entry point: hands BungeeCord's events and commands to {@link PartiesCore}. */
public final class PartiesAndChillBungee extends Plugin implements Listener, ProxyPlatform {

    /** Set once started; event handlers ignore everything before (or after a failed start). */
    private volatile PartiesCore core;

    /** Blocks until the core is up (file and Redis I/O), like Velocity's startup does. */
    @Override
    public void onEnable() {
        // Before any I/O: even if startup fails, clients must not reach backends on the bridge channel.
        getProxy().registerChannel(BridgeChannel.CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
        PartiesCore started;
        try {
            started = PartiesCore.start(this, getDataFolder().toPath(), new JulLogger(getLogger())).join();
        } catch (CompletionException e) {
            getLogger().log(Level.SEVERE, "Parties & Chill failed to start; party commands are disabled", e.getCause());
            return;
        }
        PluginManager plugins = getProxy().getPluginManager();
        plugins.registerCommand(this, new BungeeCommand("party", started, started.partyCommand(), "p"));
        plugins.registerCommand(this, new BungeeCommand("pchat", started, started.chatCommand(), "pc", "party-chat"));
        core = started;
    }

    @Override
    public void onDisable() {
        if (core != null) core.close();
    }

    /**
     * Bridge messages are never forwarded (whichever way they travel), and only backends may send them: a client could
     * otherwise forge party data, mutes or developer API calls.
     */
    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        route(event, core);
    }

    /** @param core {@code null} before (or after a failed) start: the message is still blocked */
    static void route(PluginMessageEvent event, PartiesCore core) {
        if (!event.getTag().equals(BridgeChannel.CHANNEL)) return;
        event.setCancelled(true);
        if (core != null && event.getSender() instanceof Server server && event.getReceiver() instanceof ProxiedPlayer player) {
            core.bridgeMessage(new BungeePlayer(player), new BungeePlayer.ServerBackend(server), event.getData());
        }
    }

    @EventHandler
    public void onLogin(PostLoginEvent event) {
        PartiesCore core = this.core;
        if (core != null) core.playerJoined(new BungeePlayer(event.getPlayer()));
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        PartiesCore core = this.core;
        if (core == null) return;
        ProxiedPlayer player = event.getPlayer();
        // A newer login of the same player replaced this session: they are still online.
        ProxiedPlayer current = getProxy().getPlayer(player.getUniqueId());
        if (current != null && current != player) return;
        core.playerLeft(player.getUniqueId());
    }

    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        PartiesCore core = this.core;
        if (core != null) core.serverSwitched(new BungeePlayer(event.getPlayer()));
    }

    /** Chat lock for clients older than 1.19.1; runs first so chat plugins never see the diverted line. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(ChatEvent event) {
        PartiesCore core = this.core;
        if (core == null || event.isCancelled() || event.isCommand()
                || !(event.getSender() instanceof ProxiedPlayer player)) return;
        if (core.chat(new BungeePlayer(player), event.getMessage())) event.setCancelled(true);
    }

    @Override
    public Optional<ProxyPlayer> player(UUID id) {
        return Optional.ofNullable(getProxy().getPlayer(id)).map(BungeePlayer::new);
    }

    @Override
    public List<String> playerNames() {
        return getProxy().getPlayers().stream().map(ProxiedPlayer::getName).toList();
    }
}
