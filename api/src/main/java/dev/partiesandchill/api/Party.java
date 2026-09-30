package dev.partiesandchill.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Immutable snapshot of a network party, taken on the proxy when it was sent. */
public final class Party {

    private final UUID id;
    private final UUID leader;
    private final List<UUID> members;
    private final String server;

    /** Created by Parties &amp; Chill; plugins only read parties. */
    @ApiStatus.Internal
    public Party(UUID id, UUID leader, List<UUID> members, @Nullable String server) {
        this.id = id;
        this.leader = leader;
        this.members = Collections.unmodifiableList(new ArrayList<UUID>(members));
        this.server = server;
    }

    /** @return the party's id, stable for its whole life */
    public UUID getId() {
        return id;
    }

    /** @return the party leader */
    public UUID getLeader() {
        return leader;
    }

    /** @return every member in join order, leader included, on any server (or disconnected within the grace period) */
    public List<UUID> getMembers() {
        return members;
    }

    /** @return the server the leader is connected to (as named in velocity.toml), or {@code null} if they are offline */
    public @Nullable String getServer() {
        return server;
    }

    @Override
    public String toString() {
        return "Party{id=" + id + ", leader=" + leader + ", members=" + members + ", server=" + server + '}';
    }
}
