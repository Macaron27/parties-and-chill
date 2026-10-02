package dev.partiesandchill.bridge;

import dev.partiesandchill.api.NetworkParties;
import dev.partiesandchill.api.NetworkPartiesAPI;
import dev.partiesandchill.api.event.PartyChatEvent;
import dev.partiesandchill.api.event.PartyCreateEvent;
import dev.partiesandchill.api.event.PartyDisbandEvent;
import dev.partiesandchill.api.event.PartyJoinEvent;
import dev.partiesandchill.api.event.PartyLeaveEvent;
import dev.partiesandchill.bridge.hook.AdvancedBanHook;
import dev.partiesandchill.bridge.hook.BedWars1058Adapter;
import dev.partiesandchill.bridge.hook.BedWars2023Adapter;
import dev.partiesandchill.bridge.hook.BwProxy2023Adapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Backend half of Parties &amp; Chill (Spigot/Paper 1.8.8 → 26.x). Party state lives on the proxy; this plugin
 * caches what the proxy pushes, diverts chat for players with the party chat lock (the proxy cannot cancel
 * signed 1.19.1+ chat), plays mention pings, reports AdvancedBan mutes, feeds BedWars party adapters, and serves the
 * developer API ({@link NetworkPartiesAPI} and its events).
 */
public final class BridgePlugin extends JavaPlugin implements Listener, PluginMessageListener {

    /** BedWars1058/2023 install their own adapter 10 ticks after enabling; ours must come after it. */
    private static final long ADAPTER_DELAY_TICKS = 40L;

    private final PartyCache cache = new PartyCache();
    private final BukkitTransport transport = new BukkitTransport();
    private final BridgeApi api = new BridgeApi(cache, transport);
    private Sounds sounds;
    private AdvancedBanHook advancedBan;

    @Override
    public void onEnable() {
        sounds = new Sounds(getLogger());
        getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeProtocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, BridgeProtocol.CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getServicesManager().register(PartyCache.class, cache, this, ServicePriority.Normal);
        getServer().getServicesManager().register(NetworkPartiesAPI.class, api, this, ServicePriority.Normal);
        NetworkParties.register(api);

        if (getServer().getPluginManager().isPluginEnabled("AdvancedBan")) {
            advancedBan = new AdvancedBanHook(this);
            getServer().getPluginManager().registerEvents(advancedBan, this);
            getLogger().info("Hooked into AdvancedBan: muted players can't use party chat.");
        }
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            public void run() {
                hookMinigames();
            }
        }, ADAPTER_DELAY_TICKS);
    }

    @Override
    public void onDisable() {
        NetworkParties.unregister(); // Bukkit drops this plugin's services by itself
        api.close();
    }

    private void hookMinigames() {
        PluginManager plugins = getServer().getPluginManager();
        hook("BedWars1058", plugins.isPluginEnabled("BedWars1058"), new Runnable() {
            public void run() {
                BedWars1058Adapter.install(cache);
            }
        });
        hook("BedWars2023", plugins.isPluginEnabled("BedWars2023"), new Runnable() {
            public void run() {
                BedWars2023Adapter.install(cache);
            }
        });
        hook("BWProxy2023", plugins.isPluginEnabled("BWProxy2023"), new Runnable() {
            public void run() {
                BwProxy2023Adapter.install(cache);
            }
        });
    }

    /** Adapters are only class-loaded when their plugin is present (their API classes are otherwise missing). */
    private void hook(String plugin, boolean present, Runnable install) {
        if (!present) return;
        try {
            install.run();
            getLogger().info("Hooked into " + plugin + ": it now uses proxy parties.");
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(Level.WARNING, "Could not hook into " + plugin + " (unsupported version?)", e);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!BridgeProtocol.CHANNEL.equals(channel)) return;
        BridgeProtocol.Incoming message;
        try {
            message = BridgeProtocol.decode(data);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Ignoring malformed message from the proxy: " + e.getMessage());
            return;
        }
        switch (message.type) {
            case BridgeProtocol.REPLY:
                api.complete(message);
                break;
            case BridgeProtocol.CHECK:
                check(player, message);
                break;
            case BridgeProtocol.LEFT:
                fire(new PartyLeaveEvent(player, message.party));
                break;
            case BridgeProtocol.SOUND: // plugin messages arrive on the server thread
                Player target = Bukkit.getPlayer(message.player);
                if (target != null) sounds.play(target, message.message, message.volume, message.pitch);
                break;
            default:
                cache.apply(message);
        }
    }

    /**
     * Fires the event the proxy is waiting on and answers it. Checks and leave notices travel on the connection of
     * the player they are about, so {@code player} is that player.
     */
    private void check(final Player player, final BridgeProtocol.Incoming check) {
        final Event event = checkEvent(player, check);
        if (event == null) { // from a newer proxy: nobody here can cancel it
            answer(player, BridgeProtocol.verdict(check.id, true));
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            getServer().getPluginManager().callEvent(event);
            answer(player, BridgeProtocol.verdict(check.id, !((Cancellable) event).isCancelled()));
        });
    }

    /**
     * Verdicts go back through the checked player only: another player may be on another proxy, whose own check
     * ids could match. If they left, the proxy times out and allows the action.
     */
    private void answer(final Player player, final byte[] verdict) {
        runSync(() -> {
            if (player.isOnline()) player.sendPluginMessage(this, BridgeProtocol.CHANNEL, verdict);
        });
    }

    /** @return the cancellable event a check asks about, or {@code null} for an unknown one */
    static Event checkEvent(Player player, BridgeProtocol.Incoming check) {
        switch (check.code) {
            case BridgeProtocol.EVENT_CREATE:
                return new PartyCreateEvent(player, check.party);
            case BridgeProtocol.EVENT_JOIN:
                return new PartyJoinEvent(player, check.party);
            case BridgeProtocol.EVENT_DISBAND:
                return new PartyDisbandEvent(player, check.party);
            case BridgeProtocol.EVENT_CHAT:
                return new PartyChatEvent(player, check.party, check.message);
            default:
                return null;
        }
    }

    /** Async events must not be fired from the server thread (Bukkit throws). */
    private void fire(final Event event) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> getServer().getPluginManager().callEvent(event));
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (BridgeProtocol.CHANNEL.equals(event.getChannel())) greet(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Usually the proxy registered the channel already; otherwise onRegisterChannel greets later.
        if (event.getPlayer().getListeningPluginChannels().contains(BridgeProtocol.CHANNEL)) greet(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cache.forget(event.getPlayer().getUniqueId());
    }

    /** Diverts chat of players with the party chat lock before any chat plugin broadcasts it. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        final Player player = event.getPlayer();
        if (!cache.isChatLocked(player.getUniqueId())) return;
        event.setCancelled(true);
        long mutedUntil = advancedBan == null ? 0 : advancedBan.mutedUntil(player);
        final byte[] message = BridgeProtocol.chat(event.getMessage(), mutedUntil);
        runSync(new Runnable() {
            public void run() {
                if (player.isOnline()) player.sendPluginMessage(BridgePlugin.this, BridgeProtocol.CHANNEL, message);
            }
        });
    }

    private void greet(Player player) {
        player.sendPluginMessage(this, BridgeProtocol.CHANNEL, BridgeProtocol.hello(listenedEvents()));
        if (advancedBan != null) {
            player.sendPluginMessage(this, BridgeProtocol.CHANNEL,
                    BridgeProtocol.mute(player.getUniqueId(), advancedBan.mutedUntil(player)));
        }
    }

    /**
     * Reports a mute change to the proxy. Plugin messages need a carrier connection, so it goes through any
     * online player; with nobody online it is re-sent when the muted player next joins a backend.
     */
    public void reportMute(final UUID player, final long until) {
        runSync(new Runnable() {
            public void run() {
                transport.send(BridgeProtocol.mute(player, until));
            }
        });
    }

    /**
     * Sent in every hello, so the proxy only waits for this server's verdict on events a plugin listens to.
     * Listeners registered later are seen from the next player join on.
     *
     * @return {@code 1 << EVENT_*} bits of the events with at least one listener
     */
    static int listenedEvents() {
        return bit(PartyCreateEvent.getHandlerList(), BridgeProtocol.EVENT_CREATE)
                | bit(PartyJoinEvent.getHandlerList(), BridgeProtocol.EVENT_JOIN)
                | bit(PartyDisbandEvent.getHandlerList(), BridgeProtocol.EVENT_DISBAND)
                | bit(PartyChatEvent.getHandlerList(), BridgeProtocol.EVENT_CHAT)
                | bit(PartyLeaveEvent.getHandlerList(), BridgeProtocol.EVENT_LEAVE);
    }

    private static int bit(HandlerList handlers, int event) {
        return handlers.getRegisteredListeners().length > 0 ? 1 << event : 0;
    }

    private void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else getServer().getScheduler().runTask(this, task);
    }

    /** Plugin messages to the proxy, carried by a connection that registered the channel. */
    private final class BukkitTransport implements BridgeApi.Transport {
        @Override
        public void sync(Runnable task) {
            runSync(task);
        }

        @Override
        public void later(long ticks, Runnable task) {
            getServer().getScheduler().runTaskLater(BridgePlugin.this, task, ticks);
        }

        @Override
        public boolean send(byte[] message, UUID... carriers) {
            Player carrier = null;
            for (UUID id : carriers) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.getListeningPluginChannels().contains(BridgeProtocol.CHANNEL)) {
                    carrier = player;
                    break;
                }
            }
            if (carrier == null) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getListeningPluginChannels().contains(BridgeProtocol.CHANNEL)) {
                        carrier = player;
                        break;
                    }
                }
            }
            if (carrier == null) return false;
            carrier.sendPluginMessage(BridgePlugin.this, BridgeProtocol.CHANNEL, message);
            return true;
        }
    }
}
