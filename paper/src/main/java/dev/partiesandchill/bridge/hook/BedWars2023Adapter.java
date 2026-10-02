package dev.partiesandchill.bridge.hook;

import com.tomkeuper.bedwars.api.BedWars;
import com.tomkeuper.bedwars.api.party.Party;
import dev.partiesandchill.bridge.PartyCache;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * BedWars2023 party adapter backed by proxy parties. Mutations are deliberately ignored: BedWars calls
 * {@code disband}/{@code removeFromParty} whenever someone leaves an arena, which must not break up the
 * network party. Parties change through {@code /party} on the proxy only.
 */
public final class BedWars2023Adapter implements Party {

    private final PlayerParties parties;

    private BedWars2023Adapter(PartyCache cache) {
        this.parties = new PlayerParties(cache);
    }

    /** Replaces BedWars2023's party system. */
    public static void install(PartyCache cache) {
        BedWars api = Bukkit.getServicesManager().load(BedWars.class);
        if (api == null) throw new IllegalStateException("BedWars2023 API service not registered");
        api.setPartyAdapter(new BedWars2023Adapter(cache));
    }

    @Override public boolean hasParty(Player p) { return parties.hasParty(p); }
    @Override public int partySize(Player p) { return parties.size(p); }
    @Override public boolean isOwner(Player p) { return parties.isOwner(p); }
    @Override public List<Player> getMembers(Player owner) { return parties.members(owner); }
    @Override public boolean isMember(Player owner, Player check) { return parties.isMember(owner, check); }
    @Override public Player getOwner(Player member) { return parties.owner(member); }
    @Override public boolean isInternal() { return false; }

    // Proxy-owned: ignored on purpose (see class docs).
    @Override public void createParty(Player owner, Player... members) { }
    @Override public void addMember(Player owner, Player member) { }
    @Override public void removeFromParty(Player member) { }
    @Override public void disband(Player owner) { }
    @Override public void removePlayer(Player owner, Player target) { }
    @Override public void promote(Player owner, Player target) { }
}
