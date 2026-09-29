package dev.partiesandchill.bridge;

import dev.partiesandchill.bridge.hook.AdvancedBanHook;
import dev.partiesandchill.bridge.hook.BedWars1058Adapter;
import dev.partiesandchill.bridge.hook.BedWars2023Adapter;
import dev.partiesandchill.bridge.hook.BwProxy2023Adapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Iterator;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Backend half of Parties &amp; Chill (Spigot/Paper 1.8.8 → 26.x). Party state lives on the proxy; this plugin
 * caches what the proxy pushes, diverts chat for players with the party chat lock (the proxy cannot cancel
 * signed 1.19.1+ chat), reports AdvancedBan mutes, and feeds BedWars party adapters.
 */
public final class BridgePlugin extends JavaPlugin implements Listener, PluginMessageListener {

    /** BedWars1058/2023 install their own adapter 10 ticks after enabling; ours must come after it. */
    private static final long ADAPTER_DELAY_TICKS = 40L;

    private final PartyCache cache = new PartyCache();
    private AdvancedBanHook advancedBan;

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeProtocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, BridgeProtocol.CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getServicesManager().register(PartyCache.class, cache, this, ServicePriority.Normal);

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
        try {
            cache.apply(BridgeProtocol.decode(data));
        } catch (IllegalArgumentException e) {
            getLogger().warning("Ignoring malformed message from the proxy: " + e.getMessage());
        }
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
        player.sendPluginMessage(this, BridgeProtocol.CHANNEL, BridgeProtocol.hello());
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
                Iterator<? extends Player> online = Bukkit.getOnlinePlayers().iterator();
                if (online.hasNext()) {
                    online.next().sendPluginMessage(BridgePlugin.this, BridgeProtocol.CHANNEL, BridgeProtocol.mute(player, until));
                }
            }
        });
    }

    private void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else getServer().getScheduler().runTask(this, task);
    }
}
