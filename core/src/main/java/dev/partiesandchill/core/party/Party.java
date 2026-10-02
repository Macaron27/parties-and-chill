package dev.partiesandchill.core.party;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Immutable snapshot of a party. Every change returns a new {@code Party}, so snapshots can be shared
 * across threads (and serialized to Redis) without locking.
 *
 * @param id      stable party identifier
 * @param leader  UUID of the leader; always one of {@link #members()}
 * @param members members in join order (the leader included)
 * @param invites pending invites, oldest first
 */
public record Party(UUID id, UUID leader, List<PartyMember> members, List<Invite> invites) {

    public Party {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(leader, "leader");
        members = List.copyOf(members);
        invites = List.copyOf(invites);
        if (members.stream().noneMatch(m -> m.id().equals(leader))) {
            throw new IllegalArgumentException("leader " + leader + " is not a member of party " + id);
        }
    }

    /** Creates a party containing only its leader. */
    public static Party create(UUID leader, long now) {
        return new Party(UUID.randomUUID(), leader, List.of(PartyMember.joined(leader, now)), List.of());
    }

    /** @return the member entry for {@code player}, if they belong to this party */
    public Optional<PartyMember> member(UUID player) {
        return members.stream().filter(m -> m.id().equals(player)).findFirst();
    }

    /** @return {@code true} if {@code player} belongs to this party */
    public boolean isMember(UUID player) {
        return member(player).isPresent();
    }

    /** @return {@code true} if {@code player} leads this party */
    public boolean isLeader(UUID player) {
        return leader.equals(player);
    }

    /** @return number of members, leader included */
    public int size() {
        return members.size();
    }

    /** @return member UUIDs in join order */
    public SequencedSet<UUID> memberIds() {
        SequencedSet<UUID> ids = new LinkedHashSet<>();
        members.forEach(m -> ids.add(m.id()));
        return ids;
    }

    /** @return UUIDs of connected members, in join order */
    public List<UUID> onlineMemberIds() {
        return members.stream().filter(PartyMember::online).map(PartyMember::id).toList();
    }

    /** @return the still-valid invite for {@code target}, if any */
    public Optional<Invite> inviteFor(UUID target, long now) {
        return invites.stream().filter(i -> i.target().equals(target) && !i.expired(now)).findFirst();
    }

    /**
     * @return the earliest epoch millis at which this party needs attention (an invite expiring or a
     * disconnected member timing out), or {@link Long#MAX_VALUE} if nothing is pending
     */
    public long nextDeadline() {
        long next = Long.MAX_VALUE;
        for (Invite invite : invites) next = Math.min(next, invite.expiresAt());
        for (PartyMember member : members) if (!member.online()) next = Math.min(next, member.dropAt());
        return next;
    }

    /** @return a copy with {@code member} appended, or replaced in place if already present */
    public Party withMember(PartyMember member) {
        List<PartyMember> copy = new ArrayList<>(members);
        int index = indexOf(member.id());
        if (index >= 0) copy.set(index, member); else copy.add(member);
        return new Party(id, leader, copy, invites);
    }

    /** @return a copy where {@code player}'s entry is transformed by {@code change} */
    public Party updateMember(UUID player, UnaryOperator<PartyMember> change) {
        return member(player).map(m -> withMember(change.apply(m))).orElse(this);
    }

    /**
     * Removes a member and sets who leads the remaining party.
     *
     * @param player     member to remove
     * @param nextLeader {@link #leader()} for a regular member, a remaining member when the leader leaves
     * @return the smaller party
     * @throws IllegalArgumentException if {@code nextLeader} is not a remaining member
     */
    public Party withoutMember(UUID player, UUID nextLeader) {
        List<PartyMember> copy = new ArrayList<>(members);
        copy.removeIf(m -> m.id().equals(player));
        return new Party(id, nextLeader, copy, invites);
    }

    /** @return a copy led by {@code newLeader}, who must already be a member */
    public Party withLeader(UUID newLeader) {
        return new Party(id, newLeader, members, invites);
    }

    /** @return a copy with {@code invite} added (replacing any previous invite for the same target) */
    public Party withInvite(Invite invite) {
        List<Invite> copy = new ArrayList<>(invites);
        copy.removeIf(i -> i.target().equals(invite.target()));
        copy.add(invite);
        return new Party(id, leader, members, copy);
    }

    /** @return a copy without the invite addressed to {@code target} */
    public Party withoutInvite(UUID target) {
        List<Invite> copy = new ArrayList<>(invites);
        copy.removeIf(i -> i.target().equals(target));
        return new Party(id, leader, members, copy);
    }

    private int indexOf(UUID player) {
        for (int i = 0; i < members.size(); i++) if (members.get(i).id().equals(player)) return i;
        return -1;
    }
}
