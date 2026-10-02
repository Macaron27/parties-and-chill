package dev.partiesandchill.core.party;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Single-proxy store. Snapshots are immutable, so reads are lock-free {@link ConcurrentHashMap} lookups;
 * writes are serialized by one lock.
 */
public final class InMemoryPartyStore implements PartyStore {

    // ponytail: one global lock; party commands are human-paced. Per-party locks if contention is ever measured.
    private final ReentrantLock lock = new ReentrantLock();
    private final ConcurrentHashMap<UUID, Party> parties = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> partyByMember = new ConcurrentHashMap<>();
    // Only parties with a pending invite or a disconnected member: the sweep never touches idle parties.
    private final ConcurrentHashMap<UUID, Long> deadlines = new ConcurrentHashMap<>();

    @Override
    public <T> T atomically(Supplier<T> operation) {
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Party> byMember(UUID player) {
        UUID partyId = partyByMember.get(player);
        return partyId == null ? Optional.empty() : Optional.ofNullable(parties.get(partyId));
    }

    @Override
    public List<Party> due(long now) {
        return deadlines.entrySet().stream().filter(e -> e.getValue() <= now)
                .map(e -> parties.get(e.getKey())).filter(p -> p != null && p.nextDeadline() <= now).toList();
    }

    @Override
    public boolean anyDue(long now) {
        for (long deadline : deadlines.values()) if (deadline <= now) return true;
        return false;
    }

    @Override
    public void save(Party party) {
        requireLock();
        Party previous = parties.put(party.id(), party);
        if (previous != null) {
            previous.memberIds().stream().filter(id -> !party.isMember(id))
                    .forEach(id -> partyByMember.remove(id, party.id()));
        }
        party.memberIds().forEach(id -> partyByMember.put(id, party.id()));
        long deadline = party.nextDeadline();
        if (deadline == Long.MAX_VALUE) deadlines.remove(party.id()); else deadlines.put(party.id(), deadline);
    }

    @Override
    public void delete(Party party) {
        requireLock();
        Party previous = parties.remove(party.id());
        deadlines.remove(party.id());
        if (previous != null) previous.memberIds().forEach(id -> partyByMember.remove(id, party.id()));
    }

    private void requireLock() {
        if (!lock.isHeldByCurrentThread()) throw new IllegalStateException("party writes must run inside atomically()");
    }
}
