package dev.partiesandchill.core.redis;

import dev.partiesandchill.core.party.Party;
import dev.partiesandchill.core.party.PartyStore;
import redis.clients.jedis.AbstractTransaction;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.params.ZRangeParams;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Network-wide {@link PartyStore}. Layout (all under the configured prefix):
 * <ul>
 *   <li>{@code party:<id>} → party JSON</li>
 *   <li>{@code member:<uuid>} → party id</li>
 *   <li>{@code due} → sorted set of party ids scored by {@link Party#nextDeadline()}, so the expiry sweep only
 *       reads parties that actually need it</li>
 *   <li>{@code lock} → write lock owner token</li>
 * </ul>
 * Needs standalone Redis (or Sentinel): the lookup script and transactions span several keys, which Redis
 * Cluster rejects. Blocking calls; run them on virtual threads.
 */
public final class RedisPartyStore implements PartyStore {

    private static final String UNLOCK =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    /** member → party id → party JSON in one round trip (hot path: every party chat line, every switch). */
    private static final String PARTY_OF_MEMBER =
            "local id = redis.call('get', KEYS[1]) if not id then return false end return redis.call('get', ARGV[1] .. id)";
    /** A crashed lock holder frees the lock after this long. */
    private static final long LOCK_TTL_MILLIS = 10_000;
    private static final long LOCK_WAIT_MILLIS = 10_000;
    private static final long MAX_BACKOFF_MILLIS = 16;

    private final UnifiedJedis redis;
    private final String prefix;
    private final String lockKey;
    private final String dueKey;
    private final ThreadLocal<String> heldToken = new ThreadLocal<>();
    /**
     * Snapshots read while holding the lock, by party id. Nobody else can write meanwhile, so {@link #save}
     * and {@link #delete} reuse them instead of re-reading the previous state.
     */
    private final ThreadLocal<Map<String, Party>> lockedReads = new ThreadLocal<>();

    public RedisPartyStore(UnifiedJedis redis, String prefix) {
        this.redis = Objects.requireNonNull(redis);
        this.prefix = prefix;
        this.lockKey = prefix + "lock";
        this.dueKey = prefix + "due";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Spins on {@code SET NX PX} with jittered exponential backoff (1 → 16 ms) for up to 10 s, then fails.
     */
    @Override
    public <T> T atomically(Supplier<T> operation) {
        if (heldToken.get() != null) return operation.get(); // re-entrant
        // ponytail: one network-wide lock, held for a few round trips per action. Per-party locks if proxies queue on it.
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + LOCK_WAIT_MILLIS * 1_000_000;
        long backoff = 1;
        while (!"OK".equals(redis.set(lockKey, token, SetParams.setParams().nx().px(LOCK_TTL_MILLIS)))) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("timed out waiting for the Redis party lock");
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(backoff, backoff * 2 + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the Redis party lock", e);
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
        }
        heldToken.set(token);
        lockedReads.set(new HashMap<>());
        try {
            return operation.get();
        } finally {
            lockedReads.remove();
            heldToken.remove();
            redis.eval(UNLOCK, 1, lockKey, token);
        }
    }

    @Override
    public Optional<Party> byMember(UUID player) {
        Object json = redis.eval(PARTY_OF_MEMBER, 1, memberKey(player), prefix + "party:");
        return json instanceof String party ? Optional.of(remember(Json.party(party))) : Optional.empty();
    }

    @Override
    public List<Party> due(long now) {
        List<String> ids = redis.zrange(dueKey, ZRangeParams.zrangeByScoreParams(Double.NEGATIVE_INFINITY, now));
        if (ids.isEmpty()) return List.of();
        return redis.mget(ids.stream().map(this::partyKey).toArray(String[]::new)).stream()
                .filter(Objects::nonNull).map(json -> remember(Json.party(json))).toList();
    }

    @Override
    public boolean anyDue(long now) {
        return redis.zcount(dueKey, Double.NEGATIVE_INFINITY, now) > 0;
    }

    @Override
    public void save(Party party) {
        requireLock();
        String id = party.id().toString();
        Set<UUID> previousMembers = previous(id).map(Party::memberIds).map(Set::copyOf).orElse(Set.of());
        try (AbstractTransaction tx = redis.multi()) {
            tx.set(partyKey(id), Json.party(party));
            previousMembers.stream().filter(m -> !party.isMember(m)).forEach(m -> tx.del(memberKey(m)));
            party.memberIds().forEach(m -> tx.set(memberKey(m), id));
            long deadline = party.nextDeadline();
            if (deadline == Long.MAX_VALUE) tx.zrem(dueKey, id); else tx.zadd(dueKey, deadline, id);
            tx.exec();
        }
        remember(party);
    }

    @Override
    public void delete(Party party) {
        requireLock();
        String id = party.id().toString();
        Set<UUID> members = previous(id).map(Party::memberIds).map(Set::copyOf).orElse(Set.copyOf(party.memberIds()));
        try (AbstractTransaction tx = redis.multi()) {
            tx.del(partyKey(id));
            members.forEach(m -> tx.del(memberKey(m)));
            tx.zrem(dueKey, id);
            tx.exec();
        }
        lockedReads.get().remove(id);
    }

    /** @return the stored state of party {@code id}: from this lock's reads if possible, else from Redis */
    private Optional<Party> previous(String id) {
        Party seen = lockedReads.get().get(id);
        if (seen != null) return Optional.of(seen);
        String json = redis.get(partyKey(id));
        return json == null ? Optional.empty() : Optional.of(Json.party(json));
    }

    private Party remember(Party party) {
        Map<String, Party> reads = lockedReads.get();
        if (reads != null) reads.put(party.id().toString(), party);
        return party;
    }

    private void requireLock() {
        if (heldToken.get() == null) throw new IllegalStateException("party writes must run inside atomically()");
    }

    private String partyKey(String id) {
        return prefix + "party:" + id;
    }

    private String memberKey(UUID player) {
        return prefix + "member:" + player;
    }
}
