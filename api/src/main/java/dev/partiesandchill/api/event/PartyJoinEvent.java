package dev.partiesandchill.api.event;

import dev.partiesandchill.api.Party;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * Fired before {@link #getPlayer()} joins a party: {@code /party accept}, or
 * {@link dev.partiesandchill.api.NetworkPartiesAPI#addMember}. {@link #getParty()} is the party they are joining,
 * not yet including them. Asynchronous.
 */
public final class PartyJoinEvent extends PartyEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled;

    public PartyJoinEvent(Player player, Party party) {
        super(player, party);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /** Cancelling keeps the player out of the party; a pending invite stays valid until it expires. */
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
