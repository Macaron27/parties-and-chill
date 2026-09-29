package dev.partiesandchill.velocity.network;

import dev.partiesandchill.velocity.party.PartyEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Everything proxies must agree on besides party state: who is online where, player names, mutes, and
 * delivery of {@link PartyEvent}s to whichever proxy hosts the recipients.
 */
public interface Network extends AutoCloseable {

    /**
     * Starts delivering published events to {@code handler} (on this proxy). Call once, before publishing.
     */
    void start(Consumer<PartyEvent> handler);

    /** Delivers {@code event} to every proxy of the network, this one included. */
    void publish(PartyEvent event);

    /** Registers a player who just logged in through this proxy. */
    void playerJoined(UUID id, String name);

    /** Unregisters a player who left this proxy (no-op if they are already registered on another proxy). */
    void playerLeft(UUID id);

    /** @return {@code true} if the player is connected to any proxy of the network */
    boolean isOnline(UUID id);

    /** @return UUID of a player seen recently, by case-insensitive name */
    Optional<UUID> uuidOf(String name);

    /** @return last known name of a player */
    Optional<String> nameOf(UUID id);

    /** @return epoch millis until which the player is muted ({@code 0}: not muted, {@link Long#MAX_VALUE}: forever) */
    long mutedUntil(UUID id);

    /** Records a mute reported by a backend bridge; {@code 0} lifts it. */
    void setMutedUntil(UUID id, long until);

    /** @return UUID of an online player, by case-insensitive name */
    default Optional<UUID> findOnline(String name) {
        return uuidOf(name).filter(this::isOnline);
    }

    @Override
    void close();
}
