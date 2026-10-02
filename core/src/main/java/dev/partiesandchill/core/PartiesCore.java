package dev.partiesandchill.core;

import dev.partiesandchill.core.ProxyPlayer.Backend;
import dev.partiesandchill.core.bridge.BridgeChannel;
import dev.partiesandchill.core.bridge.BridgeGuard;
import dev.partiesandchill.core.bridge.BridgeHandler;
import dev.partiesandchill.core.chat.PartyChat;
import dev.partiesandchill.core.command.PartyChatCommand;
import dev.partiesandchill.core.command.PartyCommand;
import dev.partiesandchill.core.command.ProxyCommand;
import dev.partiesandchill.core.config.Messages;
import dev.partiesandchill.core.config.PluginConfig;
import dev.partiesandchill.core.network.EventDispatcher;
import dev.partiesandchill.core.network.LocalNetwork;
import dev.partiesandchill.core.network.Network;
import dev.partiesandchill.core.party.InMemoryPartyStore;
import dev.partiesandchill.core.party.Party;
import dev.partiesandchill.core.party.PartyManager;
import dev.partiesandchill.core.party.PartyStore;
import dev.partiesandchill.core.redis.RedisNetwork;
import dev.partiesandchill.core.redis.RedisPartyStore;
import org.slf4j.Logger;
import redis.clients.jedis.RedisClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/**
 * The proxy plugin minus the proxy: loads the config, picks single-proxy or Redis mode, and turns platform events
 * into party actions. Velocity and BungeeCord forward their events and commands here. Every handler returns right
 * away; the work runs on virtual threads, since with Redis every action is network I/O.
 */
public final class PartiesCore implements AutoCloseable {

    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("parties-and-chill-scheduler").factory());
    private final Logger logger;
    private final Messages messages;
    private final RedisClient redis;
    private final Network network;
    private final PartyManager manager;
    private final PartyChat chat;
    private final BridgeChannel bridge = new BridgeChannel();
    private final BridgeHandler bridgeHandler;
    private final PartyCommand partyCommand;
    private final PartyChatCommand chatCommand;

    /**
     * Boots on a virtual thread (file and Redis I/O). Platforms register their commands and listeners once it
     * completes, so nothing reaches a half-started core.
     *
     * @param dataDirectory where {@code config.yml} and {@code messages.yml} live (written on first start)
     * @return the running core; fails if the config is invalid or Redis is unreachable
     */
    public static CompletableFuture<PartiesCore> start(ProxyPlatform platform, Path dataDirectory, Logger logger) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        return CompletableFuture.supplyAsync(() -> new PartiesCore(platform, dataDirectory, logger, executor), executor)
                .whenComplete((core, error) -> {
                    if (error != null) executor.shutdown();
                });
    }

    private PartiesCore(ProxyPlatform platform, Path dataDirectory, Logger logger, ExecutorService executor) {
        this.executor = executor;
        this.logger = logger;
        PluginConfig config;
        try {
            config = PluginConfig.load(dataDirectory);
            messages = Messages.load(dataDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read config.yml / messages.yml", e);
        }

        PartyStore store;
        if (config.redis().enabled()) {
            redis = RedisClient.create(URI.create(config.redis().uri()));
            store = new RedisPartyStore(redis, config.redis().prefix());
            network = new RedisNetwork(redis, config.redis().prefix(), config.redis().proxyId(),
                    config.party().disconnectGrace().multipliedBy(4), executor, logger);
        } else {
            redis = null;
            store = new InMemoryPartyStore();
            network = new LocalNetwork(config.party().disconnectGrace().multipliedBy(4));
        }

        try {
            Clock clock = Clock.systemUTC();
            BridgeGuard guard = new BridgeGuard(id -> platform.player(id).flatMap(ProxyPlayer::backend), bridge, network,
                    logger);
            manager = new PartyManager(store, config.party(), clock, network::publish, network::isOnline,
                    RandomGenerator.getDefault(), guard);
            chat = new PartyChat(manager, network, messages, bridge, clock);
            // Before start(): it already reaps proxies that died (this one's previous run included).
            if (network instanceof RedisNetwork redisNetwork) redisNetwork.onPlayersLost(manager::disconnected);
            network.start(new EventDispatcher(platform, scheduler, messages, network, bridge, chat));
            bridgeHandler = new BridgeHandler(manager, network, bridge, guard, chat, executor, logger);
            partyCommand = new PartyCommand(platform, manager, network, messages, executor, logger);
            chatCommand = new PartyChatCommand(chat, messages, executor, logger);
        } catch (RuntimeException e) {
            close(); // e.g. Redis unreachable: don't leave the client or the scheduler behind
            throw e;
        }

        scheduler.scheduleAtFixedRate(() -> executor.execute(this::tick), 1, 1, TimeUnit.SECONDS);
        logger.info("Parties & Chill ready ({})", config.redis().enabled()
                ? "Redis network mode, proxy id " + config.redis().proxyId()
                : "single-proxy mode");
    }

    private void tick() {
        try {
            manager.tick();
        } catch (RuntimeException e) {
            logger.warn("Party expiry sweep failed; retrying next second", e);
        }
    }

    /** @return the messages, e.g. to answer non-player command senders */
    public Messages messages() {
        return messages;
    }

    /** @return {@code /party} ({@code /p}) */
    public ProxyCommand partyCommand() {
        return partyCommand;
    }

    /** @return {@code /pchat} ({@code /pc}) */
    public ProxyCommand chatCommand() {
        return chatCommand;
    }

    /** A player finished logging in through this proxy. */
    public void playerJoined(ProxyPlayer player) {
        executor.execute(() -> {
            network.playerJoined(player.uniqueId(), player.name());
            manager.reconnected(player.uniqueId());
        });
    }

    /**
     * A player's session on this proxy ended. Platforms skip sessions that never finished logging in, and sessions
     * replaced by a newer login of the same player.
     */
    public void playerLeft(UUID player) {
        executor.execute(() -> {
            chat.forget(player);
            network.playerLeft(player);
            manager.disconnected(player);
        });
    }

    /**
     * A player finished switching servers: records their server (developer API {@code Party#getServer}), refreshes the
     * new backend's party snapshot and chat lock, then auto-warps the party if its leader entered a game server.
     */
    public void serverSwitched(ProxyPlayer player) {
        executor.execute(() -> player.backend().ifPresent(server -> {
            network.serverSwitched(player.uniqueId(), server.name());
            Party party = manager.partyOf(player.uniqueId()).orElse(null);
            bridge.sendSnapshot(player, party);
            chat.serverSwitched(player);
            if (party != null && party.isLeader(player.uniqueId())) { // skip the manager's own lookup for everyone else
                manager.leaderSwitchedServer(player.uniqueId(), server.name());
            }
        }));
    }

    /**
     * Chat lock for clients whose chat the proxy may cancel (unsigned, before 1.19.1). Newer clients are diverted by
     * the backend bridge instead.
     *
     * @return {@code true} if the line went to party chat and the platform must cancel it
     */
    public boolean chat(ProxyPlayer player, String message) {
        if (player.signedChat() || !chat.isLocked(player.uniqueId())) return false;
        executor.execute(() -> chat.send(player, message, 0));
        return true;
    }

    /**
     * A {@link BridgeChannel#CHANNEL} message a backend sent through {@code player}'s connection. Platforms never
     * forward these messages and never hand over ones sent by a client.
     *
     * @param source the connection it came through
     */
    public void bridgeMessage(ProxyPlayer player, Backend source, byte[] data) {
        bridgeHandler.handle(player, source, data);
    }

    /** Stops heart-beating (Redis) and the background threads. */
    @Override
    public void close() {
        scheduler.shutdownNow();
        if (network != null) network.close();
        if (redis != null) redis.close();
        executor.shutdown();
    }
}
