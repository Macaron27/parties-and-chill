package dev.partiesandchill.core.party;

import dev.partiesandchill.core.redis.RedisTestSupport;

/** Same rules as the in-memory store, against a real Redis. */
class RedisPartyManagerTest extends PartyManagerContract {

    @Override
    PartyStore newStore() {
        return RedisTestSupport.newStore();
    }
}
