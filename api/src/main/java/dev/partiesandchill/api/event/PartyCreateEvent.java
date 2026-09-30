package dev.partiesandchill.api.event;

import dev.partiesandchill.api.Party;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * Fired before {@link #getPlayer()} creates a party: with their first {@code /party invite}, or through
 * {@link dev.partiesandchill.api.NetworkPartiesAPI#createParty}. {@link #getParty()} is the party about to exist
 * (the leader alone). Asynchronous.
 */
public final class PartyCreateEvent extends PartyEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled;

    public PartyCreateEvent(Player leader, Party party) {
        super(leader, party);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /** Cancelling stops the party from being created (and the invite that would have created it). */
    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
