package dev.partiesandchill.core.network;

import dev.partiesandchill.core.party.PartyEvent;

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

    /**
     * Registers a player who just logged in through this proxy.
     *
     * @param sizeLimit the party size their permissions grant, for proxies that can't check those permissions
     */
    void playerJoined(UUID id, String name, int sizeLimit);

    /** Unregisters a player who left this proxy (no-op if they are already registered on another proxy). */
    void playerLeft(UUID id);

    /** Records the backend a player of this proxy just switched to (ignored once they left the proxy). */
    void serverSwitched(UUID id, String server);

    /** @return the backend server an online player is on */
    Optional<String> serverOf(UUID id);

    /** @return {@code true} if the player is connected to any proxy of the network */
    boolean isOnline(UUID id);

    /** @return UUID of a player seen recently, by case-insensitive name */
    Optional<UUID> uuidOf(String name);

    /** @return last known name of a player */
    Optional<String> nameOf(UUID id);

    /** @return the party size limit recorded at the player's last login, or {@code 0} if unknown */
    int sizeLimit(UUID id);

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
