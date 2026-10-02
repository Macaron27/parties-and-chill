package dev.partiesandchill.core.redis;

import dev.partiesandchill.core.party.PartyStore;

import java.util.UUID;

/** Lets tests outside this package get a Redis-backed store. */
public final class RedisTestSupport {

    private RedisTestSupport() {
    }

    /** @return a store on the local test Redis, under a fresh prefix */
    public static PartyStore newStore() {
        return new RedisPartyStore(LocalRedis.client(), "test-" + UUID.randomUUID() + ":");
    }
}
