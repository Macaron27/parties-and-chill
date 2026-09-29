package dev.partiesandchill.bridge.hook;

import com.tomkeuper.bedwars.proxy.api.BedWars;
import com.tomkeuper.bedwars.proxy.api.party.Party;
import dev.partiesandchill.bridge.PartyCache;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * BWProxy2023 (BedWars2023 lobby plugin) adapter: lets it size arenas and send whole parties using proxy
 * parties. Mutations are ignored; parties change through {@code /party} on the proxy only.
 */
public final class BwProxy2023Adapter implements Party {

    private final PartyCache cache;

    BwProxy2023Adapter(PartyCache cache) {
        this.cache = cache;
    }

    /** Replaces BWProxy2023's party system. */
    public static void install(PartyCache cache) {
        BedWars api = Bukkit.getServicesManager().load(BedWars.class);
        if (api == null) throw new IllegalStateException("BWProxy2023 API service not registered");
        api.setPartyAdapter(new BwProxy2023Adapter(cache));
    }

    @Override
    public boolean hasParty(UUID player) {
        return cache.partyOf(player) != null;
    }

    @Override
    public int partySize(UUID player) {
        PartyCache.PartyView party = cache.partyOf(player);
        return party == null ? 0 : party.members().size();
    }

    @Override
    public boolean isOwner(UUID player) {
        PartyCache.PartyView party = cache.partyOf(player);
        return party != null && party.leader().equals(player);
    }

    /** @return every member on the network: the lobby sends them all to the arena */
    @Override
    public List<UUID> getMembers(UUID owner) {
        PartyCache.PartyView party = cache.partyOf(owner);
        return party == null ? Collections.<UUID>emptyList() : party.members();
    }

    @Override
    public boolean isMember(UUID owner, UUID check) {
        PartyCache.PartyView party = cache.partyOf(owner);
        return party != null && party.members().contains(check);
    }

    @Override
    public UUID getOwner(UUID player) {
        PartyCache.PartyView party = cache.partyOf(player);
        return party == null ? null : party.leader();
    }

    @Override
    public boolean isInternal() {
        return false;
    }

    // Proxy-owned: ignored on purpose.
    @Override public void createParty(Player owner, Player... members) { }
    @Override public void addMember(UUID owner, Player member) { }
    @Override public void removeFromParty(UUID member) { }
    @Override public void disband(UUID owner) { }
    @Override public void removePlayer(UUID owner, UUID target) { }
}
