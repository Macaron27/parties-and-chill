package dev.partiesandchill.velocity.bridge;

import com.velocitypowered.api.proxy.ServerConnection;
import dev.partiesandchill.velocity.network.Network;
import dev.partiesandchill.velocity.party.Party;
import dev.partiesandchill.velocity.party.PartyGuard;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Backend plugins' veto on party actions: asks the backend of the acting player, only when its plugins listen to
 * that event, and waits for the verdict. Fails open: no answer within {@link #TIMEOUT} (lagging or outdated
 * backend) means allowed, so one server can't block parties network-wide.
 *
 * <p>ponytail: only players on this proxy can be asked about. With Redis, an API call carried by another player
 * than the one the event is about can skip the check; routing checks to the owning proxy (pub/sub) would close it.
 */
public final class BridgeGuard implements PartyGuard {

    static final Duration TIMEOUT = Duration.ofSeconds(1);

    private record Pending(String server, CompletableFuture<Boolean> verdict) {
    }

    private final Function<UUID, Optional<ServerConnection>> connections;
    private final BridgeChannel bridge;
    private final Network network;
    private final Logger logger;
    private final AtomicInteger ids = new AtomicInteger(ThreadLocalRandom.current().nextInt());
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();

    /** @param connections the backend connection of a player on this proxy */
    public BridgeGuard(Function<UUID, Optional<ServerConnection>> connections, BridgeChannel bridge, Network network,
                       Logger logger) {
        this.connections = connections;
        this.bridge = bridge;
        this.network = network;
        this.logger = logger;
    }

    @Override
    public boolean watches(Action action, UUID player) {
        return listening(action, player).isPresent();
    }

    @Override
    public boolean allows(Action action, UUID player, Party party, String message) {
        ServerConnection server = listening(action, player).orElse(null);
        if (server == null) return true;
        int id = ids.incrementAndGet();
        String name = server.getServerInfo().getName();
        CompletableFuture<Boolean> verdict = new CompletableFuture<>();
        pending.put(id, new Pending(name, verdict));
        try {
            BridgeMessage.Check check = new BridgeMessage.Check(id, event(action), player, BridgeChannel.info(party, network), message);
            if (!BridgeChannel.send(server, check)) return true;
            return verdict.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // A bridge removed from that backend would otherwise cost every action a full timeout; the next hello
            // (next player join) turns the checks back on.
            bridge.stopAsking(name);
            logger.warn("{} didn't answer the party {} check within {} ms; allowing it and pausing checks there until "
                    + "a player joins it", name, action, TIMEOUT.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e); // never completed exceptionally
        } finally {
            pending.remove(id);
        }
    }

    /** Completes check {@code id}; answers from a server other than the one asked are ignored. */
    public void verdict(String server, int id, boolean allowed) {
        Pending check = pending.get(id);
        if (check != null && check.server().equals(server)) check.verdict().complete(allowed);
    }

    private Optional<ServerConnection> listening(Action action, UUID player) {
        return connections.apply(player).filter(server -> bridge.listens(server, event(action)));
    }

    private static int event(Action action) {
        return switch (action) {
            case CREATE -> BridgeMessage.EVENT_CREATE;
            case JOIN -> BridgeMessage.EVENT_JOIN;
            case DISBAND -> BridgeMessage.EVENT_DISBAND;
            case CHAT -> BridgeMessage.EVENT_CHAT;
        };
    }
}
