package dev.partiesandchill.api.event;

import dev.partiesandchill.api.Party;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired after {@link #getPlayer()} stopped being in a party: they left, were kicked, or the party was disbanded
 * or broke up. {@link #getParty()} is the party as it was right before, still including them. Players who are
 * removed while offline (disconnect grace ran out) get no event. Not cancellable: players can always leave.
 * Asynchronous.
 */
public final class PartyLeaveEvent extends PartyEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public PartyLeaveEvent(Player player, Party party) {
        super(player, party);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
