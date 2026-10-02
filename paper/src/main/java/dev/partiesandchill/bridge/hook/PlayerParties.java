package dev.partiesandchill.bridge.hook;

import dev.partiesandchill.bridge.PartyCache;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared answers for the Player-based BedWars party interfaces (1058 and 2023 have identical shapes). */
final class PlayerParties {

    private final PartyCache cache;

    PlayerParties(PartyCache cache) {
        this.cache = cache;
    }

    boolean hasParty(Player player) {
        return cache.partyOf(player.getUniqueId()) != null;
    }

    /** @return members on the whole network, like the proxy counts them */
    int size(Player player) {
        PartyCache.PartyView party = cache.partyOf(player.getUniqueId());
        return party == null ? 0 : party.members().size();
    }

    boolean isOwner(Player player) {
        PartyCache.PartyView party = cache.partyOf(player.getUniqueId());
        return party != null && party.leader().equals(player.getUniqueId());
    }

    /** @return members of {@code member}'s party connected to this server (BedWars teams them up) */
    List<Player> members(Player member) {
        PartyCache.PartyView party = cache.partyOf(member.getUniqueId());
        List<Player> online = new ArrayList<Player>();
        if (party == null) return online;
        for (UUID id : party.members()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) online.add(player);
        }
        return online;
    }

    boolean isMember(Player owner, Player check) {
        PartyCache.PartyView party = cache.partyOf(owner.getUniqueId());
        return party != null && party.members().contains(check.getUniqueId());
    }

    Player owner(Player member) {
        PartyCache.PartyView party = cache.partyOf(member.getUniqueId());
        return party == null ? null : Bukkit.getPlayer(party.leader());
    }
}
