package dev.partiesandchill.velocity.network;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.partiesandchill.velocity.party.PartyEvent;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Single-proxy {@link Network}: everything lives in this JVM. */
public final class LocalNetwork implements Network {

    private final Map<UUID, String> online = new ConcurrentHashMap<>();
    private final Map<String, UUID> onlineByName = new ConcurrentHashMap<>();
    // Players who left recently keep a name, so /p list and kick still work during the disconnect grace.
    private final Cache<UUID, String> recentNames;
    private final Cache<String, UUID> recentByName;
    private final Map<UUID, Long> mutes = new ConcurrentHashMap<>();
    private volatile Consumer<PartyEvent> handler;

    /** @param nameRetention how long a departed player's name stays resolvable (keep it above the grace period) */
    public LocalNetwork(Duration nameRetention) {
        this.recentNames = Caffeine.newBuilder().expireAfterWrite(nameRetention).build();
        this.recentByName = Caffeine.newBuilder().expireAfterWrite(nameRetention).build();
    }

    @Override
    public void start(Consumer<PartyEvent> handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    @Override
    public void publish(PartyEvent event) {
        handler.accept(event);
    }

    @Override
    public void playerJoined(UUID id, String name) {
        String previous = online.put(id, name);
        if (previous != null) onlineByName.remove(previous.toLowerCase(Locale.ROOT), id);
        onlineByName.put(name.toLowerCase(Locale.ROOT), id);
    }

    @Override
    public void playerLeft(UUID id) {
        String name = online.remove(id);
        if (name == null) return;
        onlineByName.remove(name.toLowerCase(Locale.ROOT), id);
        recentNames.put(id, name);
        recentByName.put(name.toLowerCase(Locale.ROOT), id);
    }

    @Override
    public boolean isOnline(UUID id) {
        return online.containsKey(id);
    }

    @Override
    public Optional<UUID> uuidOf(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        UUID id = onlineByName.get(key);
        return Optional.ofNullable(id != null ? id : recentByName.getIfPresent(key));
    }

    @Override
    public Optional<String> nameOf(UUID id) {
        String name = online.get(id);
        return Optional.ofNullable(name != null ? name : recentNames.getIfPresent(id));
    }

    @Override
    public long mutedUntil(UUID id) {
        return mutes.getOrDefault(id, 0L);
    }

    @Override
    public void setMutedUntil(UUID id, long until) {
        if (until <= 0) mutes.remove(id); else mutes.put(id, until);
    }

    @Override
    public void close() {
    }
}
