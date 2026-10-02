package dev.partiesandchill.core.party;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Where party snapshots live: in memory for a single proxy, in Redis for a network of proxies.
 * Reads return immutable snapshots and may be called anywhere; writes must happen inside
 * {@link #atomically(Supplier)}.
 */
public interface PartyStore {

    /**
     * Runs {@code operation} while holding the store-wide write lock (network-wide for Redis).
     *
     * @return what {@code operation} returned
     */
    <T> T atomically(Supplier<T> operation);

    /** @return the party {@code player} belongs to */
    Optional<Party> byMember(UUID player);

    /** @return parties whose {@link Party#nextDeadline()} is at or before {@code now} */
    List<Party> due(long now);

    /** @return {@code true} if {@link #due(long)} would return something; cheaper, used by the 1 s sweep */
    default boolean anyDue(long now) {
        return !due(now).isEmpty();
    }

    /** Inserts or replaces {@code party} and re-indexes its members. */
    void save(Party party);

    /** Removes {@code party} and its member index entries. */
    void delete(Party party);
}
