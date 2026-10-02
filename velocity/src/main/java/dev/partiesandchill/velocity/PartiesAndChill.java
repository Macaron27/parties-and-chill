package dev.partiesandchill.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.partiesandchill.core.PartiesCore;
import dev.partiesandchill.core.ProxyPlatform;
import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.bridge.BridgeChannel;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Velocity entry point: hands Velocity's events and commands to {@link PartiesCore}. */
@Plugin(
        id = "partiesandchill",
        name = "Parties & Chill",
        version = "1.2.0", // checked against the Gradle version by PluginDescriptorTest
        description = "Hypixel-style parties for Velocity networks",
        authors = {"Parties & Chill"})
public final class PartiesAndChill implements ProxyPlatform {

    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(BridgeChannel.CHANNEL);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    /** Set once started; event handlers ignore everything before (or after a failed start). */
    private volatile PartiesCore core;

    @Inject
    public PartiesAndChill(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /** Velocity waits for the core (file and Redis I/O) before finishing startup. */
    @Subscribe
    public EventTask onInitialize(ProxyInitializeEvent event) {
        // Before any I/O: even if startup fails, clients must not reach backends on the bridge channel.
        proxy.getChannelRegistrar().register(CHANNEL);
        return EventTask.resumeWhenComplete(PartiesCore.start(this, dataDirectory, logger)
                .thenAccept(this::enable)
                .exceptionally(error -> {
                    logger.error("Parties & Chill failed to start; party commands are disabled", error);
                    return null;
                }));
    }

    private void enable(PartiesCore core) {
        CommandManager commands = proxy.getCommandManager();
        commands.register(commands.metaBuilder("party").aliases("p").plugin(this).build(),
                new VelocityCommand(this, core, core.partyCommand()));
        commands.register(commands.metaBuilder("pchat").aliases("pc").plugin(this).build(),
                new VelocityCommand(this, core, core.chatCommand()));
        this.core = core;
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (core != null) core.close();
    }

    /**
     * Bridge messages are never forwarded, whether or not startup succeeded, and only backends may send them: a client
     * could otherwise forge party data, mutes or developer API calls.
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        route(event, core, proxy);
    }

    /** @param core {@code null} before (or after a failed) start: the message is still blocked */
    static void route(PluginMessageEvent event, PartiesCore core, ProxyServer proxy) {
        if (!event.getIdentifier().equals(CHANNEL)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (core != null && event.getSource() instanceof ServerConnection connection) {
            core.bridgeMessage(new VelocityPlayer(proxy, connection.getPlayer()), new VelocityPlayer.ServerBackend(connection),
                    event.getData());
        }
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        PartiesCore core = this.core;
        if (core != null) core.playerJoined(wrap(event.getPlayer()));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // Only sessions that passed PostLogin; a CONFLICTING_LOGIN means the original session is still online.
        if (event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
                && event.getLoginStatus() != DisconnectEvent.LoginStatus.PRE_SERVER_JOIN) return;
        PartiesCore core = this.core;
        if (core != null) core.playerLeft(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onServerSwitched(ServerPostConnectEvent event) {
        PartiesCore core = this.core;
        if (core != null) core.serverSwitched(wrap(event.getPlayer()));
    }

    /** Chat lock for clients older than 1.19.1; {@link PartiesCore#chat} leaves signed chat alone. */
    @Subscribe(order = PostOrder.EARLY)
    @SuppressWarnings("deprecation") // denying is only unsafe for signed (1.19.1+) chat, which core never diverts
    public void onChat(PlayerChatEvent event) {
        PartiesCore core = this.core;
        if (core == null || !event.getResult().isAllowed()) return;
        if (core.chat(wrap(event.getPlayer()), event.getMessage())) event.setResult(PlayerChatEvent.ChatResult.denied());
    }

    @Override
    public Optional<ProxyPlayer> player(UUID id) {
        return proxy.getPlayer(id).map(this::wrap);
    }

    @Override
    public List<String> playerNames() {
        return proxy.getAllPlayers().stream().map(Player::getUsername).toList();
    }

    ProxyPlayer wrap(Player player) {
        return new VelocityPlayer(proxy, player);
    }
}
