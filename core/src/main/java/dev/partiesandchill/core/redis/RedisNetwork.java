package dev.partiesandchill.core.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.partiesandchill.core.network.Network;
import dev.partiesandchill.core.party.PartyEvent;
import org.slf4j.Logger;
import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * {@link Network} shared by every proxy through Redis. Layout (under the prefix):
 * <ul>
 *   <li>{@code online} → hash player UUID → proxy id</li>
 *   <li>{@code server} → hash player UUID → backend server; written and cleared only by the owning proxy</li>
 *   <li>{@code proxies} + {@code alive:<proxy>} → registered proxies and their 15 s heartbeat</li>
 *   <li>{@code name:<uuid>} / {@code uuid:<lowercase name>} → name cache</li>
 *   <li>{@code size:<uuid>} → party size limit granted by the player's permissions at their last login</li>
 *   (names and size limits never expire while the player is online, then live {@code nameTtl} longer)
 *   <li>{@code mute:<uuid>} → epoch millis the mute ends (expires with it)</li>
 *   <li>{@code events} → pub/sub channel carrying {@link PartyEvent} JSON</li>
 * </ul>
 * When a proxy stops heart-beating, the others hand its players to {@link #onPlayersLost} so they enter
 * the normal disconnect grace period instead of staying "online" forever.
 */
public final class RedisNetwork implements Network {

    /**
     * Releases a player (and their server entry) only if {@code ARGV[2]} still owns them, and starts the countdown
     * ({@code ARGV[3]} ms) of their name and size keys. KEYS: online, server, name:&lt;uuid&gt;, size:&lt;uuid&gt;;
     * ARGV: uuid, proxy, ttl, uuid: key prefix.
     */
    private static final String RELEASE_IF_OWNER = "if redis.call('hget', KEYS[1], ARGV[1]) ~= ARGV[2] then return 0 end "
            + "redis.call('hdel', KEYS[2], ARGV[1]) redis.call('hdel', KEYS[1], ARGV[1]) "
            + "local name = redis.call('get', KEYS[3]) "
            + "if name then redis.call('pexpire', ARGV[4] .. string.lower(name), ARGV[3]) end "
            + "redis.call('pexpire', KEYS[3], ARGV[3]) redis.call('pexpire', KEYS[4], ARGV[3]) return 1";
    /** Same ownership check, so a switch handled after the logout can't leave a stale server entry. */
    private static final String HSET_SERVER_IF_OWNER = "if redis.call('hget', KEYS[1], ARGV[1]) == ARGV[2] then "
            + "return redis.call('hset', KEYS[2], ARGV[1], ARGV[3]) else return 0 end";
    private static final long HEARTBEAT_MILLIS = 5_000;
    private static final long ALIVE_TTL_MILLIS = 15_000;

    private final UnifiedJedis redis;
    private final String prefix;
    private final String proxyId;
    private final Duration nameTtl;
    private final ExecutorService executor;
    private final Logger logger;
    private final Cache<UUID, String> names = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).build();
    /** Proxies seen alive at the last heartbeat; saves a round trip per presence check. */
    private volatile Set<String> aliveProxies = Set.of();
    private volatile Consumer<UUID> onPlayersLost = id -> { };
    private volatile boolean closed;
    private volatile JedisPubSub subscription;

    /**
     * @param nameTtl  how long names (and size limits) stay resolvable after the player left
     * @param executor runs the subscriber and heartbeat loops (virtual threads)
     */
    public RedisNetwork(UnifiedJedis redis, String prefix, String proxyId, Duration nameTtl, ExecutorService executor,
                        Logger logger) {
        this.redis = redis;
        this.prefix = prefix;
        this.proxyId = proxyId;
        this.nameTtl = nameTtl;
        this.executor = executor;
        this.logger = logger;
    }

    /** Receives players whose proxy vanished (crash, network split). Called on a background thread. */
    public void onPlayersLost(Consumer<UUID> handler) {
        this.onPlayersLost = handler;
    }

    @Override
    public void start(Consumer<PartyEvent> handler) {
        reap(proxyId); // a previous run under the same proxy id may have died with players registered
        redis.sadd(key("proxies"), proxyId);
        heartbeat();
        refreshProxies();
        executor.execute(() -> subscribeLoop(handler));
        executor.execute(this::heartbeatLoop);
    }

    @Override
    public void publish(PartyEvent event) {
        redis.publish(key("events"), Json.event(event));
    }

    @Override
    public void playerJoined(UUID id, String name, int sizeLimit) {
        // Pipelined: one round trip instead of four. Measured on localhost: ~25 µs per login vs ~80 µs sequential
        // (MULTI/EXEC ~45 µs); these keys don't need to change atomically. No expiry while online (SET clears an
        // older one): RELEASE_IF_OWNER starts it when the player leaves.
        try (AbstractPipeline pipeline = redis.pipelined()) {
            pipeline.hset(key("online"), id.toString(), proxyId);
            pipeline.set(key("name:" + id), name);
            pipeline.set(key("uuid:" + name.toLowerCase(Locale.ROOT)), id.toString());
            pipeline.set(key("size:" + id), Integer.toString(sizeLimit));
            pipeline.sync();
        }
        names.put(id, name);
    }

    @Override
    public void playerLeft(UUID id) {
        releaseIfOwnedBy(id, proxyId);
    }

    @Override
    public void serverSwitched(UUID id, String server) {
        redis.eval(HSET_SERVER_IF_OWNER, 2, key("online"), key("server"), id.toString(), proxyId, server);
    }

    @Override
    public Optional<String> serverOf(UUID id) {
        return Optional.ofNullable(redis.hget(key("server"), id.toString()));
    }

    @Override
    public boolean isOnline(UUID id) {
        String owner = redis.hget(key("online"), id.toString());
        if (owner == null) return false;
        // A proxy newer than our last heartbeat isn't cached yet: ask Redis directly.
        return owner.equals(proxyId) || aliveProxies.contains(owner) || redis.exists(key("alive:" + owner));
    }

    @Override
    public Optional<UUID> uuidOf(String name) {
        return Optional.ofNullable(redis.get(key("uuid:" + name.toLowerCase(Locale.ROOT)))).map(UUID::fromString);
    }

    @Override
    public Optional<String> nameOf(UUID id) {
        String cached = names.getIfPresent(id);
        if (cached != null) return Optional.of(cached);
        String name = redis.get(key("name:" + id));
        if (name != null) names.put(id, name);
        return Optional.ofNullable(name);
    }

    @Override
    public int sizeLimit(UUID id) {
        String limit = redis.get(key("size:" + id));
        return limit == null ? 0 : Integer.parseInt(limit);
    }

    @Override
    public long mutedUntil(UUID id) {
        String until = redis.get(key("mute:" + id));
        return until == null ? 0 : Long.parseLong(until);
    }

    @Override
    public void setMutedUntil(UUID id, long until) {
        String key = key("mute:" + id);
        if (until <= System.currentTimeMillis()) redis.del(key);
        else if (until == Long.MAX_VALUE) redis.set(key, Long.toString(until));
        else redis.set(key, Long.toString(until), SetParams.setParams().pxAt(until));
    }

    /** Stops heart-beating; the remaining proxies reap this proxy's players within one heartbeat. */
    @Override
    public void close() {
        closed = true;
        JedisPubSub current = subscription;
        if (current != null && current.isSubscribed()) current.unsubscribe();
        try {
            redis.del(key("alive:" + proxyId));
        } catch (RuntimeException e) {
            logger.warn("Could not clear this proxy's Redis heartbeat", e);
        }
    }

    /**
     * Refreshes {@link #aliveProxies} (two round trips) and reaps proxies whose heartbeat expired: their players
     * leave the online hash and go to {@link #onPlayersLost}.
     */
    void refreshProxies() {
        List<String> proxies = new ArrayList<>(redis.smembers(key("proxies")));
        if (proxies.isEmpty()) return;
        List<String> beats = redis.mget(proxies.stream().map(p -> key("alive:" + p)).toArray(String[]::new));
        Set<String> alive = new HashSet<>();
        for (int i = 0; i < proxies.size(); i++) {
            String proxy = proxies.get(i);
            if (beats.get(i) != null || proxy.equals(proxyId)) {
                alive.add(proxy);
                continue;
            }
            logger.warn("Proxy {} stopped responding; moving its players to the disconnect grace period", proxy);
            reap(proxy);
            redis.srem(key("proxies"), proxy);
        }
        aliveProxies = Set.copyOf(alive);
    }

    private void reap(String proxy) {
        for (Map.Entry<String, String> entry : redis.hgetAll(key("online")).entrySet()) {
            if (!entry.getValue().equals(proxy)) continue;
            UUID player = UUID.fromString(entry.getKey());
            if (releaseIfOwnedBy(player, proxy)) onPlayersLost.accept(player);
        }
    }

    private boolean releaseIfOwnedBy(UUID player, String proxy) {
        Object removed = redis.eval(RELEASE_IF_OWNER, 4, key("online"), key("server"), key("name:" + player),
                key("size:" + player), player.toString(), proxy, Long.toString(nameTtl.toMillis()), key("uuid:"));
        return removed instanceof Long count && count > 0;
    }

    private void heartbeat() {
        redis.set(key("alive:" + proxyId), Long.toString(System.currentTimeMillis()),
                SetParams.setParams().px(ALIVE_TTL_MILLIS));
    }

    private void heartbeatLoop() {
        while (!closed) {
            try {
                Thread.sleep(HEARTBEAT_MILLIS);
                if (closed) return;
                heartbeat();
                redis.sadd(key("proxies"), proxyId);
                refreshProxies();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                logger.warn("Redis heartbeat failed; retrying", e);
            }
        }
    }

    private void subscribeLoop(Consumer<PartyEvent> handler) {
        while (!closed) {
            JedisPubSub pubSub = new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    try {
                        handler.accept(Json.event(message));
                    } catch (RuntimeException e) {
                        logger.warn("Dropping undeliverable party event: {}", message, e);
                    }
                }
            };
            subscription = pubSub;
            try {
                redis.subscribe(pubSub, key("events")); // blocks until unsubscribed or the connection drops
            } catch (RuntimeException e) {
                if (closed) return;
                logger.warn("Lost the Redis event subscription; reconnecting in 1s", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    private String key(String suffix) {
        return prefix + suffix;
    }
}
