package dev.partiesandchill.core.party;

import dev.partiesandchill.core.config.Durations;
import dev.partiesandchill.core.party.PartyGuard.Action;
import dev.partiesandchill.core.party.PartyEvent.Notice;
import dev.partiesandchill.core.party.PartyEvent.PartyChanged;
import dev.partiesandchill.core.party.PartyEvent.Warp;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * All party rules (Hypixel parity). Platform-agnostic: it reads and writes {@link PartyStore} snapshots and
 * reports every side effect as a {@link PartyEvent}; it never talks to players or servers directly.
 *
 * <p>Events are published only after the store lock is released, so slow delivery (e.g. Redis pub/sub)
 * never holds up other party operations.
 */
public final class PartyManager {

    /** Result of a player action. Anything but {@link #SUCCESS} names the error message to show the actor. */
    public enum Outcome {
        SUCCESS(""),
        NOT_IN_PARTY("error.not-in-party"),
        NOT_LEADER("error.not-leader"),
        CANNOT_TARGET_SELF("error.self"),
        ALREADY_IN_PARTY("error.already-in-party"),
        TARGET_IN_PARTY("error.target-in-party"),
        TARGET_NOT_IN_PARTY("error.target-not-in-party"),
        ALREADY_INVITED("error.already-invited"),
        NO_INVITE("error.no-invite"),
        PARTY_FULL("error.party-full"),
        NO_ONE_TO_WARP("error.no-one-to-warp"),
        MUTED("error.muted"),
        PLAYER_OFFLINE("error.player-offline"),
        /** A backend plugin cancelled the action; like Bukkit's own events, it tells the player why. */
        CANCELLED("");

        private final String messageKey;

        Outcome(String messageKey) {
            this.messageKey = messageKey;
        }

        /** @return the {@code messages.yml} key describing this outcome to the actor */
        public String messageKey() {
            return messageKey;
        }
    }

    private final PartyStore store;
    private final PartySettings settings;
    private final Clock clock;
    private final Consumer<PartyEvent> events;
    private final Predicate<UUID> isOnline;
    private final RandomGenerator random;
    private final PartyGuard guard;
    private final Map<UUID, Long> lastMutedNotice = new ConcurrentHashMap<>();

    /**
     * @param store    party storage (in-memory or Redis)
     * @param settings party rules
     * @param clock    time source; injectable so expiry is testable without sleeping
     * @param events   receives every side effect, after the store lock is released
     * @param isOnline network-wide presence check, used to ignore stale disconnects
     * @param random   picks the new leader when a disconnected leader times out
     * @param guard    lets backend plugins cancel create / join / disband / chat
     */
    public PartyManager(PartyStore store, PartySettings settings, Clock clock, Consumer<PartyEvent> events,
                        Predicate<UUID> isOnline, RandomGenerator random, PartyGuard guard) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
        this.isOnline = isOnline;
        this.random = random;
        this.guard = guard;
    }

    /** @return the rules this manager enforces */
    public PartySettings settings() {
        return settings;
    }

    /**
     * Looks up a player's party without locking.
     *
     * @return the party snapshot {@code player} belongs to
     */
    public Optional<Party> partyOf(UUID player) {
        return store.byMember(player);
    }

    /**
     * Invites {@code target}, creating a party led by {@code inviter} if they have none.
     * The invite expires after {@link PartySettings#inviteTtl()}.
     */
    public Outcome invite(UUID inviter, UUID target) {
        if (inviter.equals(target)) return Outcome.CANNOT_TARGET_SELF;
        // The first invite creates the party. The vetted party is the one saved, so plugins see its real id.
        Party created = null;
        if (guard.watches(Action.CREATE, inviter) && store.byMember(inviter).isEmpty()) {
            created = Party.create(inviter, clock.millis());
            if (!guard.allows(Action.CREATE, inviter, created, "")) return Outcome.CANCELLED;
        }
        Party vetted = created;
        return write(out -> {
            long now = clock.millis();
            if (store.byMember(target).isPresent()) return Outcome.TARGET_IN_PARTY;
            Party original = store.byMember(inviter).orElse(null);
            if (original != null && !original.isLeader(inviter)) return Outcome.NOT_LEADER;
            // ponytail: a party disbanded between the check and the lock is re-created unvetted; that race is microseconds wide.
            Party party = original != null ? original : vetted != null ? vetted : Party.create(inviter, now);
            if (party.inviteFor(target, now).isPresent()) return Outcome.ALREADY_INVITED;
            if (party.size() >= settings.maxSize()) return Outcome.PARTY_FULL;

            party = party.withInvite(new Invite(target, inviter, now + settings.inviteTtl().toMillis()));
            Map<String, String> time = Map.of("time", Durations.format(settings.inviteTtl()));
            out.add(notice(Set.of(target), "invite.received", Map.of("player", inviter), time));
            out.add(notice(party.memberIds(), "invite.sent", Map.of("player", inviter, "target", target), time));
            commit(original, party, out);
            return Outcome.SUCCESS;
        });
    }

    /**
     * Accepts the invite sent to {@code target} by the party of {@code inviter}.
     */
    public Outcome accept(UUID target, UUID inviter) {
        if (guard.watches(Action.JOIN, target)) {
            Party party = store.byMember(inviter).filter(p -> p.inviteFor(target, clock.millis()).isPresent()).orElse(null);
            if (party == null) return Outcome.NO_INVITE;
            if (!guard.allows(Action.JOIN, target, party, "")) return Outcome.CANCELLED;
        }
        return write(out -> {
            long now = clock.millis();
            Party original = store.byMember(inviter).filter(p -> p.inviteFor(target, now).isPresent()).orElse(null);
            if (original == null) return Outcome.NO_INVITE;
            if (store.byMember(target).isPresent()) return Outcome.ALREADY_IN_PARTY;
            if (original.size() >= settings.maxSize()) return Outcome.PARTY_FULL;
            join(original, target, now, out);
            return Outcome.SUCCESS;
        });
    }

    /**
     * (Developer API) Creates a party led by {@code leader} alone. Like any party of one, it is disbanded the next
     * time it changes while no invite is pending (e.g. when the leader disconnects).
     */
    public Outcome create(UUID leader) {
        if (!isOnline.test(leader)) return Outcome.PLAYER_OFFLINE; // an offline "online" leader would never time out
        Party party = Party.create(leader, clock.millis());
        if (guard.watches(Action.CREATE, leader)) {
            if (store.byMember(leader).isPresent()) return Outcome.ALREADY_IN_PARTY;
            if (!guard.allows(Action.CREATE, leader, party, "")) return Outcome.CANCELLED;
        }
        return write(out -> {
            if (store.byMember(leader).isPresent()) return Outcome.ALREADY_IN_PARTY;
            store.save(party); // not commit(): its lonely-party rule would delete a party of one right away
            out.add(notice(Set.of(leader), "party.created", Map.of()));
            out.add(new PartyChanged(party.memberIds(), party, null));
            return Outcome.SUCCESS;
        });
    }

    /** (Developer API, leader only) Puts {@code target} in the party right away, consuming any invite they had. */
    public Outcome add(UUID leader, UUID target) {
        if (leader.equals(target)) return Outcome.CANNOT_TARGET_SELF;
        if (!isOnline.test(target)) return Outcome.PLAYER_OFFLINE;
        if (guard.watches(Action.JOIN, target)) {
            Party party = store.byMember(leader).orElse(null);
            Outcome denied = checkLeader(party, leader);
            if (denied != null) return denied;
            if (!guard.allows(Action.JOIN, target, party, "")) return Outcome.CANCELLED;
        }
        return write(out -> {
            Party original = store.byMember(leader).orElse(null);
            Outcome denied = checkLeader(original, leader);
            if (denied != null) return denied;
            if (store.byMember(target).isPresent()) return Outcome.TARGET_IN_PARTY;
            if (original.size() >= settings.maxSize()) return Outcome.PARTY_FULL;
            join(original, target, clock.millis(), out);
            return Outcome.SUCCESS;
        });
    }

    private void join(Party original, UUID target, long now, List<PartyEvent> out) {
        Party party = original.withoutInvite(target).withMember(PartyMember.joined(target, now));
        out.add(notice(original.memberIds(), "member.joined", Map.of("player", target)));
        out.add(notice(Set.of(target), "party.joined", Map.of("player", party.leader())));
        commit(original, party, out);
    }

    /**
     * Declines the invite sent to {@code target} by the party of {@code inviter}. A party left with only
     * its leader and no pending invite is disbanded.
     */
    public Outcome deny(UUID target, UUID inviter) {
        return write(out -> {
            long now = clock.millis();
            Party original = store.byMember(inviter).filter(p -> p.inviteFor(target, now).isPresent()).orElse(null);
            if (original == null) return Outcome.NO_INVITE;

            Party party = original.withoutInvite(target);
            out.add(notice(Set.of(target), "invite.denied-self", Map.of("player", inviter)));
            out.add(notice(party.memberIds(), "invite.denied", Map.of("player", target)));
            commit(original, party, out);
            return Outcome.SUCCESS;
        });
    }

    /** Leaves the current party. A leaving leader hands over to the oldest online member. */
    public Outcome leave(UUID player) {
        return write(out -> {
            Party original = store.byMember(player).orElse(null);
            if (original == null) return Outcome.NOT_IN_PARTY;
            out.add(notice(Set.of(player), "party.left", Map.of()));
            commit(original, drop(original, player, out, "member.left", Map.of("player", player), false), out);
            return Outcome.SUCCESS;
        });
    }

    /** (Leader only) Removes {@code target} from the party. */
    public Outcome kick(UUID leader, UUID target) {
        return write(out -> {
            Party original = store.byMember(leader).orElse(null);
            Outcome denied = checkLeaderTargeting(original, leader, target);
            if (denied != null) return denied;
            out.add(notice(Set.of(target), "party.kicked", Map.of("player", leader)));
            Party party = drop(original, target, out, "member.kicked", Map.of("player", target, "leader", leader), false);
            commit(original, party, out);
            return Outcome.SUCCESS;
        });
    }

    /** (Leader only) Transfers leadership to {@code target}. */
    public Outcome promote(UUID leader, UUID target) {
        return write(out -> {
            Party original = store.byMember(leader).orElse(null);
            Outcome denied = checkLeaderTargeting(original, leader, target);
            if (denied != null) return denied;
            Party party = original.withLeader(target);
            out.add(notice(party.memberIds(), "leader.promoted", Map.of("player", leader, "target", target)));
            commit(original, party, out);
            return Outcome.SUCCESS;
        });
    }

    /** (Leader only) Destroys the party. */
    public Outcome disband(UUID leader) {
        if (guard.watches(Action.DISBAND, leader)) {
            Party party = store.byMember(leader).orElse(null);
            Outcome denied = checkLeader(party, leader);
            if (denied != null) return denied;
            if (!guard.allows(Action.DISBAND, leader, party, "")) return Outcome.CANCELLED;
        }
        return write(out -> {
            Party party = store.byMember(leader).orElse(null);
            Outcome denied = checkLeader(party, leader);
            if (denied != null) return denied;
            store.delete(party);
            out.add(notice(party.memberIds(), "disband.leader", Map.of("player", leader)));
            out.add(new PartyChanged(party.memberIds(), null, party));
            return Outcome.SUCCESS;
        });
    }

    /**
     * (Leader only) Pulls every online member to {@code server} right away.
     *
     * @param server the leader's current server
     */
    public Outcome warp(UUID leader, String server) {
        Party party = store.byMember(leader).orElse(null);
        if (party == null) return Outcome.NOT_IN_PARTY;
        if (!party.isLeader(leader)) return Outcome.NOT_LEADER;
        List<UUID> members = othersOnline(party, leader);
        if (members.isEmpty()) return Outcome.NO_ONE_TO_WARP;
        publish(List.of(
                new Warp(Set.copyOf(members), server, 0),
                notice(members, "warp.summoned", Map.of("player", leader), Map.of("server", server)),
                notice(Set.of(leader), "warp.sent", Map.of(),
                        Map.of("server", server, "count", Integer.toString(members.size())))));
        return Outcome.SUCCESS;
    }

    /**
     * Auto-warp hook: call after a player finished switching servers. If they lead a party and the server
     * is a game server, online members follow after {@link PartySettings#warpDelay()}.
     */
    public void leaderSwitchedServer(UUID player, String server) {
        if (!settings.isGameServer(server)) return;
        Party party = store.byMember(player).filter(p -> p.isLeader(player)).orElse(null);
        if (party == null) return;
        List<UUID> members = othersOnline(party, player);
        if (members.isEmpty()) return;
        publish(List.of(
                new Warp(Set.copyOf(members), server, settings.warpDelay().toMillis()),
                notice(members, "warp.following", Map.of("player", player), Map.of("server", server))));
    }

    /**
     * Sends a party chat message.
     *
     * @param mutedUntil epoch millis until which the sender is muted ({@code 0}: not muted,
     *                   {@link Long#MAX_VALUE}: permanently). Muted senders are blocked and their party
     *                   receives a "currently muted" notice, at most once per
     *                   {@link PartySettings#mutedNoticeCooldown()}.
     */
    public Outcome chat(UUID sender, String message, long mutedUntil) {
        Party party = store.byMember(sender).orElse(null);
        if (party == null) return Outcome.NOT_IN_PARTY;
        long now = clock.millis();
        if (mutedUntil > now) {
            long cooldown = settings.mutedNoticeCooldown().toMillis();
            Long last = lastMutedNotice.get(sender);
            if (last == null || now - last >= cooldown) {
                lastMutedNotice.put(sender, now);
                List<UUID> others = party.memberIds().stream().filter(id -> !id.equals(sender)).toList();
                publish(List.of(notice(others, "chat.muted-notice", Map.of("player", sender))));
            }
            return Outcome.MUTED;
        }
        if (guard.watches(Action.CHAT, sender)) {
            if (!guard.allows(Action.CHAT, sender, party, message)) return Outcome.CANCELLED;
            party = store.byMember(sender).orElse(null); // the check can take a second: members may have changed
            if (party == null) return Outcome.NOT_IN_PARTY;
        }
        publish(List.of(notice(party.memberIds(), "chat.format", Map.of("player", sender), Map.of("message", message))));
        return Outcome.SUCCESS;
    }

    /**
     * Marks {@code player} as disconnected; they keep their slot for {@link PartySettings#disconnectGrace()}.
     * Ignored if the player is already back online (e.g. reconnected through another proxy).
     */
    public void disconnected(UUID player) {
        write(out -> {
            if (isOnline.test(player)) return null;
            Party original = store.byMember(player).orElse(null);
            if (original == null || !original.member(player).orElseThrow().online()) return null;
            long dropAt = clock.millis() + settings.disconnectGrace().toMillis();
            Party party = original.updateMember(player, m -> m.disconnected(dropAt));
            String key = party.isLeader(player) ? "leader.disconnected" : "member.disconnected";
            out.add(notice(others(party, player), key, Map.of("player", player),
                    Map.of("time", Durations.format(settings.disconnectGrace()))));
            commit(original, party, out);
            return null;
        });
    }

    /** Restores a disconnected member who came back within the grace period. */
    public void reconnected(UUID player) {
        // Lock-free fast path for the common case (not in a party / not flagged offline). A stale read here is
        // harmless: tick() heals members flagged offline who are actually online.
        Party snapshot = store.byMember(player).orElse(null);
        if (snapshot == null || snapshot.member(player).map(PartyMember::online).orElse(true)) return;
        write(out -> {
            Party original = store.byMember(player).orElse(null);
            if (original == null || original.member(player).orElseThrow().online()) return null;
            Party party = original.updateMember(player, PartyMember::reconnected);
            out.add(notice(others(party, player), "member.reconnected", Map.of("player", player)));
            commit(original, party, out);
            return null;
        });
    }

    /**
     * Expires invites and drops members whose grace period ran out. A timed-out leader is replaced by a
     * random online member. Call periodically (every second).
     */
    public void tick() {
        long now = clock.millis();
        long cooldown = settings.mutedNoticeCooldown().toMillis();
        lastMutedNotice.values().removeIf(sent -> now - sent >= cooldown);
        if (!store.anyDue(now)) return; // cheap check before taking the (possibly network-wide) lock
        write(out -> {
            for (Party party : store.due(now)) expire(party, now, out);
            return null;
        });
    }

    private void expire(Party original, long now, List<PartyEvent> out) {
        Party party = original;
        for (Invite invite : original.invites()) {
            if (!invite.expired(now)) continue;
            party = party.withoutInvite(invite.target());
            out.add(notice(Set.of(invite.target()), "invite.expired-target", Map.of("player", invite.inviter())));
            out.add(notice(party.memberIds(), "invite.expired", Map.of("player", invite.target())));
        }
        for (PartyMember member : original.members()) {
            if (party == null) break;
            if (member.online() || member.dropAt() > now) continue;
            if (isOnline.test(member.id())) { // missed reconnect: heal instead of dropping
                party = party.updateMember(member.id(), PartyMember::reconnected);
                continue;
            }
            party = drop(party, member.id(), out, "member.timed-out", Map.of("player", member.id()), true);
        }
        commit(original, party, out);
    }

    /**
     * Removes {@code player} and elects a successor if they led the party.
     *
     * @return the remaining party, or {@code null} if nobody is left
     */
    private Party drop(Party party, UUID player, List<PartyEvent> out, String key, Map<String, UUID> placeholders,
                       boolean randomSuccessor) {
        out.add(new PartyChanged(Set.of(player), null, party));
        if (party.size() == 1) return null;
        boolean leaderLeft = party.isLeader(player);
        UUID next = leaderLeft ? successor(party, player, randomSuccessor) : party.leader();
        Party rest = party.withoutMember(player, next);
        out.add(notice(rest.memberIds(), key, placeholders));
        if (leaderLeft) out.add(notice(rest.memberIds(), "leader.changed", Map.of("player", next)));
        return rest;
    }

    private UUID successor(Party party, UUID leaving, boolean random) {
        List<UUID> online = othersOnline(party, leaving);
        List<UUID> pool = online.isEmpty() ? others(party, leaving) : online;
        return random ? pool.get(this.random.nextInt(pool.size())) : pool.getFirst();
    }

    /**
     * Persists the new state: deletes the party when nobody is left to party with (one member and no
     * pending invite), otherwise saves it and announces membership or leadership changes.
     *
     * @param original stored snapshot, or {@code null} for a brand-new party
     * @param party    new state, or {@code null} if the last member left
     */
    private void commit(Party original, Party party, List<PartyEvent> out) {
        if (party == null) {
            store.delete(original);
            return;
        }
        if (party.size() <= 1 && party.invites().isEmpty()) {
            store.delete(party);
            out.add(notice(party.memberIds(), "disband.empty", Map.of()));
            out.add(new PartyChanged(party.memberIds(), null, party));
            return;
        }
        store.save(party);
        boolean changed = original == null
                || !original.memberIds().equals(party.memberIds())
                || !original.leader().equals(party.leader());
        if (changed) out.add(new PartyChanged(party.memberIds(), party, null));
    }

    private static Outcome checkLeader(Party party, UUID leader) {
        if (party == null) return Outcome.NOT_IN_PARTY;
        if (!party.isLeader(leader)) return Outcome.NOT_LEADER;
        return null;
    }

    private static Outcome checkLeaderTargeting(Party party, UUID leader, UUID target) {
        Outcome denied = checkLeader(party, leader);
        if (denied != null) return denied;
        if (leader.equals(target)) return Outcome.CANNOT_TARGET_SELF;
        if (!party.isMember(target)) return Outcome.TARGET_NOT_IN_PARTY;
        return null;
    }

    private static List<UUID> others(Party party, UUID player) {
        return party.memberIds().stream().filter(id -> !id.equals(player)).toList();
    }

    private static List<UUID> othersOnline(Party party, UUID player) {
        return party.onlineMemberIds().stream().filter(id -> !id.equals(player)).toList();
    }

    private static Notice notice(Collection<UUID> to, String key, Map<String, UUID> players) {
        return notice(to, key, players, Map.of());
    }

    private static Notice notice(Collection<UUID> to, String key, Map<String, UUID> players, Map<String, String> values) {
        return new Notice(Set.copyOf(to), key, players, values);
    }

    private <T> T write(Function<List<PartyEvent>, T> operation) {
        List<PartyEvent> out = new ArrayList<>();
        T result = store.atomically(() -> operation.apply(out));
        publish(out);
        return result;
    }

    private void publish(List<? extends PartyEvent> out) {
        for (PartyEvent event : out) {
            if (event instanceof Notice n && n.recipients().isEmpty()) continue;
            events.accept(event);
        }
    }
}
