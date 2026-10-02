package dev.partiesandchill.core.command;

import dev.partiesandchill.core.ProxyPlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A player command; platforms register it under their own command API and refuse non-player senders. */
public interface ProxyCommand {

    /** Returns right away: the work runs on a virtual thread. */
    void execute(ProxyPlayer player, String[] args);

    /** @param args the arguments typed so far (the last one may be partial or empty) */
    default CompletableFuture<List<String>> suggest(ProxyPlayer player, String[] args) {
        return CompletableFuture.completedFuture(List.of());
    }
}
