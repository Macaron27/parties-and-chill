package dev.partiesandchill.api.event;

import dev.partiesandchill.api.Party;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * Fired before a party chat line from {@link #getPlayer()} ({@code /pc <message>} or the party chat lock) reaches
 * the party. Muted players are blocked before this event. Asynchronous.
 */
public final class PartyChatEvent extends PartyEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final String message;
    private boolean cancelled;

    public PartyChatEvent(Player sender, Party party, String message) {
        super(sender, party);
        this.message = message;
    }

    /** @return the chat line, as typed (plain text) */
    public String getMessage() {
        return message;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /** Cancelling drops the message: nobody in the party sees it. */
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
