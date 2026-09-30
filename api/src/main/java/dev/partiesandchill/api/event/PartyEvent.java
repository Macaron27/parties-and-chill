package dev.partiesandchill.api.event;

import dev.partiesandchill.api.Party;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

/**
 * Base of every Parties &amp; Chill event. Events are <b>asynchronous</b> (never on the main thread) because they come
 * from the proxy, and they fire only on the server where {@link #getPlayer()} is connected.
 *
 * <p>The cancellable ones fire <em>before</em> the action, while the proxy waits for this server's answer (up to
 * 1 second, after which the action goes ahead). Keep listeners fast: no blocking I/O. The action can still fail
 * after the event if the party changed in the meantime (e.g. it filled up). Cancelling is silent: tell the player
 * why yourself.
 */
public abstract class PartyEvent extends Event {

    private final Player player;
    private final Party party;

    protected PartyEvent(Player player, Party party) {
        super(true);
        this.player = player;
        this.party = party;
    }

    /** @return the player this event is about, connected to this server */
    public Player getPlayer() {
        return player;
    }

    /** @return the party as it is right before the action */
    public Party getParty() {
        return party;
    }
}
