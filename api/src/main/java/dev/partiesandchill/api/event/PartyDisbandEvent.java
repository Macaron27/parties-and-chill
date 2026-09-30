package dev.partiesandchill.api.event;

import dev.partiesandchill.api.Party;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * Fired before the leader {@link #getPlayer()} disbands their party: {@code /party disband}, or
 * {@link dev.partiesandchill.api.NetworkPartiesAPI#disbandParty}. Parties that break up on their own (last member
 * left, invite expired) don't fire it; every member still gets a {@link PartyLeaveEvent}. Asynchronous.
 */
public final class PartyDisbandEvent extends PartyEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled;

    public PartyDisbandEvent(Player leader, Party party) {
        super(leader, party);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /** Cancelling keeps the party as it is. */
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
