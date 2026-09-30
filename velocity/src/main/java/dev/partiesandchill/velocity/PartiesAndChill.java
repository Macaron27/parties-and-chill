package dev.partiesandchill.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.partiesandchill.velocity.bridge.BridgeChannel;
import dev.partiesandchill.velocity.bridge.BridgeGuard;
import dev.partiesandchill.velocity.bridge.BridgeListener;
import dev.partiesandchill.velocity.chat.PartyChat;
import dev.partiesandchill.velocity.command.PartyChatCommand;
import dev.partiesandchill.velocity.command.PartyCommand;
import dev.partiesandchill.velocity.config.Messages;
import dev.partiesandchill.velocity.config.PluginConfig;
import dev.partiesandchill.velocity.listener.ChatListener;
import dev.partiesandchill.velocity.listener.ConnectionListener;
import dev.partiesandchill.velocity.listener.ServerSwitchListener;
import dev.partiesandchill.velocity.network.EventDispatcher;
import dev.partiesandchill.velocity.network.LocalNetwork;
import dev.partiesandchill.velocity.network.Network;
import dev.partiesandchill.velocity.party.InMemoryPartyStore;
import dev.partiesandchill.velocity.party.PartyManager;
import dev.partiesandchill.velocity.party.PartyStore;
import dev.partiesandchill.velocity.redis.RedisNetwork;
import dev.partiesandchill.velocity.redis.RedisPartyStore;
import org.slf4j.Logger;
import redis.clients.jedis.RedisClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/** Velocity entry point: loads config, picks single-proxy or Redis mode, wires commands and listeners. */
@Plugin(
        id = "partiesandchill",
        name = "Parties & Chill",
        version = "1.1.0",
        description = "Hypixel-style parties for Velocity networks",
        authors = {"Parties & Chill"})
public final class PartiesAndChill {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private Network network;
    private RedisClient redis;

    @Inject
    public PartiesAndChill(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /** Boots on a virtual thread (file and Redis I/O); Velocity waits for it before finishing startup. */
    @Subscribe
    public EventTask onInitialize(ProxyInitializeEvent event) {
        // Before any I/O: even if startup fails, clients must not reach backends on the bridge channel.
        proxy.getChannelRegistrar().register(BridgeChannel.CHANNEL);
        return EventTask.resumeWhenComplete(CompletableFuture.runAsync(this::start, executor)
                .exceptionally(error -> {
                    logger.error("Parties & Chill failed to start; party commands are disabled", error);
                    return null;
                }));
    }

    private void start() {
        PluginConfig config;
        Messages messages;
        try {
            config = PluginConfig.load(dataDirectory);
            messages = Messages.load(dataDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read config.yml / messages.yml", e);
        }

        Clock clock = Clock.systemUTC();
        PartyStore store;
        if (config.redis().enabled()) {
            redis = RedisClient.create(URI.create(config.redis().uri()));
            store = new RedisPartyStore(redis, config.redis().prefix());
            network = new RedisNetwork(redis, config.redis().prefix(), config.redis().proxyId(),
                    config.party().disconnectGrace().multipliedBy(4), executor, logger);
        } else {
            store = new InMemoryPartyStore();
            network = new LocalNetwork(config.party().disconnectGrace().multipliedBy(4));
        }

        BridgeChannel bridge = new BridgeChannel();
        BridgeGuard guard = new BridgeGuard(id -> proxy.getPlayer(id).flatMap(Player::getCurrentServer), bridge, network, logger);
        PartyManager manager = new PartyManager(store, config.party(), clock, network::publish, network::isOnline,
                RandomGenerator.getDefault(), guard);
        PartyChat chat = new PartyChat(manager, network, messages, bridge, clock);
        network.start(new EventDispatcher(this, proxy, messages, network, bridge, chat));
        if (network instanceof RedisNetwork redisNetwork) redisNetwork.onPlayersLost(manager::disconnected);

        CommandManager commands = proxy.getCommandManager();
        commands.register(commands.metaBuilder("party").aliases("p").plugin(this).build(),
                new PartyCommand(proxy, manager, network, messages, executor, logger));
        commands.register(commands.metaBuilder("pchat").aliases("pc").plugin(this).build(),
                new PartyChatCommand(chat, messages, executor, logger));

        EventManager events = proxy.getEventManager();
        events.register(this, new ConnectionListener(manager, network, chat, executor));
        events.register(this, new ServerSwitchListener(manager, network, bridge, chat, executor));
        events.register(this, new ChatListener(chat, executor));
        events.register(this, new BridgeListener(manager, network, bridge, guard, chat, executor, logger));

        proxy.getScheduler().buildTask(this, () -> executor.execute(() -> {
            try {
                manager.tick();
            } catch (RuntimeException e) {
                logger.warn("Party expiry sweep failed; retrying next second", e);
            }
        })).repeat(1, TimeUnit.SECONDS).schedule();

        logger.info("Parties & Chill ready ({})", config.redis().enabled()
                ? "Redis network mode, proxy id " + config.redis().proxyId()
                : "single-proxy mode");
    }

    /** Bridge messages are never forwarded, whether or not startup succeeded ({@link BridgeListener} handles them). */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (event.getIdentifier().equals(BridgeChannel.CHANNEL)) event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (network != null) network.close();
        if (redis != null) redis.close();
        executor.shutdown();
    }
}
