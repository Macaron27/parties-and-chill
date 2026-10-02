package dev.partiesandchill.bridge;

import dev.partiesandchill.api.NetworkPartiesAPI;
import dev.partiesandchill.api.Party;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link NetworkPartiesAPI} over the plugin channel: each call is a request the proxy answers with a reply of the
 * same id. Replies arrive on the server thread, so that's where futures complete.
 */
final class BridgeApi implements NetworkPartiesAPI {

    /** 5 s: generous for a round trip, short enough to surface an outdated or missing proxy plugin. */
    static final long TIMEOUT_TICKS = 100;

    /** How requests reach the proxy; Bukkit-backed in {@link BridgePlugin}, faked in tests. */
    interface Transport {
        /** Runs {@code task} on the server thread: right away if already there, otherwise next tick. */
        void sync(Runnable task);

        /** Runs {@code task} on the server thread in {@code ticks}. */
        void later(long ticks, Runnable task);

        /**
         * Server thread only. Plugin messages need a player connection to travel on.
         *
         * @param carriers preferred connections (the players concerned), else anyone online with the channel
         * @return {@code false} if nobody on this server can carry it
         */
        boolean send(byte[] message, UUID... carriers);
    }

    private final PartyCache cache;
    private final Transport transport;
    /** Random start: a reply meant for the instance before a reload must not match a new request. */
    private final AtomicInteger ids = new AtomicInteger(ThreadLocalRandom.current().nextInt());
    private final Map<Integer, CompletableFuture<BridgeProtocol.Incoming>> pending =
            new ConcurrentHashMap<Integer, CompletableFuture<BridgeProtocol.Incoming>>();

    BridgeApi(PartyCache cache, Transport transport) {
        this.cache = cache;
        this.transport = transport;
    }

    @Override
    public CompletableFuture<Optional<Party>> getParty(UUID playerUuid) {
        return request(BridgeProtocol.ACTION_GET, playerUuid, playerUuid)
                .thenApply(reply -> Optional.ofNullable(reply.party));
    }

    @Override
    public CompletableFuture<Boolean> createParty(UUID leaderUuid) {
        return change(BridgeProtocol.ACTION_CREATE, leaderUuid, leaderUuid);
    }

    @Override
    public CompletableFuture<Boolean> addMember(UUID leaderUuid, UUID targetUuid) {
        return change(BridgeProtocol.ACTION_ADD, leaderUuid, targetUuid);
    }

    @Override
    public CompletableFuture<Boolean> removeMember(UUID leaderUuid, UUID targetUuid) {
        return change(BridgeProtocol.ACTION_REMOVE, leaderUuid, targetUuid);
    }

    @Override
    public CompletableFuture<Boolean> disbandParty(UUID leaderUuid) {
        return change(BridgeProtocol.ACTION_DISBAND, leaderUuid, leaderUuid);
    }

    @Override
    public boolean isInParty(UUID playerUuid) {
        return cache.partyOf(playerUuid) != null;
    }

    @Override
    public boolean isPartyLeader(UUID playerUuid) {
        PartyCache.PartyView party = cache.partyOf(playerUuid);
        return party != null && party.leader().equals(playerUuid);
    }

    /** Completes the request {@code reply} answers; replies to requests that already timed out are dropped. */
    void complete(BridgeProtocol.Incoming reply) {
        CompletableFuture<BridgeProtocol.Incoming> future = pending.remove(reply.id);
        if (future == null) return;
        if (reply.code == BridgeProtocol.STATUS_ERROR) {
            future.completeExceptionally(new IllegalStateException("the proxy failed to process the request (see its log)"));
        } else {
            future.complete(reply);
        }
    }

    /**
     * Fails every pending request (the plugin is being disabled). Callers hold futures derived with
     * {@code thenApply}, which a cancel wouldn't mark cancelled: they fail with this cause, like other errors.
     */
    void close() {
        for (Integer id : pending.keySet()) fail(id, new CancellationException("Parties & Chill was disabled"));
    }

    private CompletableFuture<Boolean> change(int action, UUID actor, UUID target) {
        return request(action, actor, target).thenApply(reply -> reply.code == BridgeProtocol.STATUS_SUCCESS);
    }

    private CompletableFuture<BridgeProtocol.Incoming> request(int action, UUID actor, UUID target) {
        if (actor == null || target == null) throw new NullPointerException("player UUID");
        int id = ids.incrementAndGet();
        CompletableFuture<BridgeProtocol.Incoming> reply = new CompletableFuture<BridgeProtocol.Incoming>();
        pending.put(id, reply);
        byte[] message = BridgeProtocol.request(id, action, actor, target);
        // The proxy can only ask the backend of players it hosts: the player an event is about carries the request.
        UUID subject = action == BridgeProtocol.ACTION_ADD ? target : actor;
        transport.sync(() -> {
            if (!transport.send(message, subject, subject.equals(actor) ? target : actor)) {
                fail(id, new IllegalStateException("no player online on this server to carry the request to the proxy"));
                return;
            }
            transport.later(TIMEOUT_TICKS, () ->
                    fail(id, new TimeoutException("the proxy didn't answer within 5 s (does it run Parties & Chill 1.1+?)")));
        });
        return reply;
    }

    private void fail(int id, Throwable error) {
        CompletableFuture<BridgeProtocol.Incoming> future = pending.remove(id);
        if (future != null) future.completeExceptionally(error);
    }
}
