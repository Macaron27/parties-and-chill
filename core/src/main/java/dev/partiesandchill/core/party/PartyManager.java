package dev.partiesandchill.core.party;

import dev.partiesandchill.core.config.Durations;
import dev.partiesandchill.core.party.PartyEvent.Chat;
import dev.partiesandchill.core.party.PartyEvent.Notice;
import dev.partiesandchill.core.party.PartyEvent.PartyChanged;
import dev.partiesandchill.core.party.PartyEvent.Warp;
import dev.partiesandchill.core.party.PartyGuard.Action;
import dev.partiesandchill.core.party.PartyRules.Right;
import dev.partiesandchill.core.party.PartySettings.Toggle;

import java.time.Clock;
import java.time.Duration;
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
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
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
        /** Only the owner may do that (disband, demote, hand the party over). */
        NOT_LEADER("error.not-leader"),
        /** The actor's role lacks the right (see {@code roles.moderator} and the party settings). */
        NO_PERMISSION("error.no-permission"),
        /** The target's role is not below the actor's. */
        TARGET_OUTRANKS("error.target-outranks"),
        NOT_MODERATOR("error.not-moderator"),
        CANNOT_TARGET_SELF("error.self"),
        ALREADY_IN_PARTY("error.already-in-party"),
        TARGET_IN_PARTY("error.target-in-party"),
        TARGET_NOT_IN_PARTY("error.target-not-in-party"),
        ALREADY_INVITED("error.already-invited"),
        NO_INVITE("error.no-invite"),
        NOT_PUBLIC("error.not-public"),
        PARTY_FULL("error.party-full"),
        NO_ONE_TO_WARP("error.no-one-to-warp"),
        MUTED("error.muted"),
        CHAT_DISABLED("error.chat-disabled"),
        CHAT_MUTED("error.chat-muted"),
        PLAYER_OFFLINE("error.player-offline"),
        /** Slow mode is on and the sender is too fast; they were told how long to wait. */
        SLOW_MODE(""),
        /** A setting value out of range; the actor was told the valid range. */
        INVALID_VALUE(""),
        /** A backend plugin cancelled the action; like Bukkit's own events, it tells the player why. */
        CANCELLED("");

        private final String messageKey;

        Outcome(String messageKey) {
            this.messageKey = messageKey;
        }

        /** @return the {@code messages.yml} key describing this outcome to the actor; empty if they were already told */
        public String messageKey() {
            return messageKey;
        }
    }

    private final PartyStore store;
    private final PartyRules rules;
    private final Clock clock;
    private final Consumer<PartyEvent> events;
    private final Predicate<UUID> isOnline;
    private final ToIntFunction<UUID> sizeLimit;
    private final RandomGenerator random;
    private final PartyGuard guard;
    private final Map<UUID, Long> lastMutedNotice = new ConcurrentHashMap<>();
    // ponytail: per proxy; a player who moves to another proxy starts with a fresh slow-mode window.
    private final Map<UUID, Long> lastChat = new ConcurrentHashMap<>();

    /**
     * @param store     party storage (in-memory or Redis)
     * @param rules     network-wide party rules
     * @param clock     time source; injectable so expiry is testable without sleeping
     * @param events    receives every side effect, after the store lock is released
     * @param isOnline  network-wide presence check, used to ignore stale disconnects
     * @param sizeLimit party size a player's permissions grant (see {@link PartyRules#sizeLimit}); asked for owners
     * @param random    picks the new leader when a disconnected leader times out
     * @param guard     lets backend plugins cancel create / join / disband / chat
     */
    public PartyManager(PartyStore store, PartyRules rules, Clock clock, Consumer<PartyEvent> events,
                        Predicate<UUID> isOnline, ToIntFunction<UUID> sizeLimit, RandomGenerator random, PartyGuard guard) {
        this.store = store;
        this.rules = rules;
        this.clock = clock;
        this.events = events;
        this.isOnline = isOnline;
        this.sizeLimit = sizeLimit;
        this.random = random;
        this.guard = guard;
    }

    /** @return the rules this manager enforces */
    public PartyRules rules() {
        return rules;
    }

    /**
     * Looks up a player's party without locking.
     *
     * @return the party snapshot {@code player} belongs to
     */
    public Optional<Party> partyOf(UUID player) {
        return store.byMember(player);
    }

    /** @return the size limit the owner's permissions grant to {@code party} (never below {@code party.max-size}) */
    public int sizeLimit(Party party) {
        return Math.max(rules.maxSize(), sizeLimit.applyAsInt(party.leader())); // 0 = not known yet
    }

    /** @return how many members {@code party} may have: its owner's permission limit, lowered by its maxsize setting */
    public int maxSize(Party party) {
        return party.settings().cappedAt(sizeLimit(party));
    }

    /**
     * @return {@code true} if {@code player} may use {@code right} in {@code party}: by role (owner, or moderator as
     * configured), or because the party lets every member invite / warp
     */
    public boolean can(Party party, UUID player, Right right) {
        PartyRole role = party.role(player).orElse(null);
        if (role == null) return false;
        return rules.grants(role, right)
                || right == Right.INVITE && party.settings().allInvite()
                || right == Right.WARP && party.settings().allWarp();
    }

    /**
     * Invites {@code target}, creating a party led by {@code inviter} if they have none.
     * The invite expires after {@link PartyRules#inviteTtl()}.
     */
    public Outcome invite(UUID inviter, UUID target) {
        if (inviter.equals(target)) return Outcome.CANNOT_TARGET_SELF;
        // The first invite creates the party. The vetted party is the one saved, so plugins see its real id.
        Party created = null;
        if (guard.watches(Action.CREATE, inviter) && store.byMember(inviter).isEmpty()) {
            created = Party.create(inviter, clock.millis(), rules.partyDefaults());
            if (!guard.allows(Action.CREATE, inviter, created, "")) return Outcome.CANCELLED;
        }
        Party vetted = created;
        return write(out -> {
            long now = clock.millis();
            if (store.byMember(target).isPresent()) return Outcome.TARGET_IN_PARTY;
            Party original = store.byMember(inviter).orElse(null);
            if (original != null && !can(original, inviter, Right.INVITE)) return Outcome.NO_PERMISSION;
            // ponytail: a party disbanded between the check and the lock is re-created unvetted; that race is microseconds wide.
            Party party = original != null ? original : vetted != null ? vetted : Party.create(inviter, now, rules.partyDefaults());
            if (party.inviteFor(target, now).isPresent()) return Outcome.ALREADY_INVITED;
            if (party.size() >= maxSize(party)) return Outcome.PARTY_FULL;

            party = party.withInvite(new Invite(target, inviter, now + rules.inviteTtl().toMillis()));
            Map<String, String> time = Map.of("time", Durations.format(rules.inviteTtl()));
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
            if (original.size() >= maxSize(original)) return Outcome.PARTY_FULL;
            join(original, target, now, out);
            return Outcome.SUCCESS;
        });
    }

    /**
     * Joins the party of {@code member} without being invited, if it is public (an invite from it works too).
     */
    public Outcome join(UUID player, UUID member) {
        if (player.equals(member)) return Outcome.CANNOT_TARGET_SELF;
        if (guard.watches(Action.JOIN, player)) {
            Party party = store.byMember(member).filter(p -> joinable(p, player, clock.millis())).orElse(null);
            if (party == null) return Outcome.NOT_PUBLIC;
            if (!guard.allows(Action.JOIN, player, party, "")) return Outcome.CANCELLED;
        }
        return write(out -> {
            long now = clock.millis();
            Party original = store.byMember(member).filter(p -> joinable(p, player, now)).orElse(null);
            if (original == null) return Outcome.NOT_PUBLIC;
            if (store.byMember(player).isPresent()) return Outcome.ALREADY_IN_PARTY;
            if (original.size() >= maxSize(original)) return Outcome.PARTY_FULL;
            join(original, player, now, out);
            return Outcome.SUCCESS;
        });
    }

    private static boolean joinable(Party party, UUID player, long now) {
        return party.settings().open() || party.inviteFor(player, now).isPresent();
    }

    /**
     * (Developer API) Creates a party led by {@code leader} alone. Like any party of one, it is disbanded the next
     * time its members change while no invite is pending (e.g. when the leader disconnects).
     */
    public Outcome create(UUID leader) {
        if (!isOnline.test(leader)) return Outcome.PLAYER_OFFLINE; // an offline "online" leader would never time out
        Party party = Party.create(leader, clock.millis(), rules.partyDefaults());
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
            if (original.size() >= maxSize(original)) return Outcome.PARTY_FULL;
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

    /** Leaves the current party. A leaving owner hands over to the oldest online moderator, else member. */
    public Outcome leave(UUID player) {
        return write(out -> {
            Party original = store.byMember(player).orElse(null);
            if (original == null) return Outcome.NOT_IN_PARTY;
            out.add(notice(Set.of(player), "party.left", Map.of()));
            commit(original, drop(original, player, out, "member.left", Map.of("player", player), false), out);
            return Outcome.SUCCESS;
        });
    }

    /** Removes {@code target}: owners kick anyone, moderators (if allowed) kick plain members. */
    public Outcome kick(UUID actor, UUID target) {
        return write(out -> {
            Party original = store.byMember(actor).orElse(null);
            Outcome denied = checkTargeting(original, actor, target, Right.KICK);
            if (denied != null) return denied;
            out.add(notice(Set.of(target), "party.kicked", Map.of("player", actor)));
            Party party = drop(original, target, out, "member.kicked", Map.of("player", target, "leader", actor), false);
            commit(original, party, out);
            return Outcome.SUCCESS;
        });
    }

    /**
     * Hypixel's promote: a member becomes moderator (owner, or moderators allowed to promote); a moderator becomes
     * owner (owner only), and the previous owner stays on as moderator.
     */
    public Outcome promote(UUID actor, UUID target) {
        return write(out -> {
            Party original = store.byMember(actor).orElse(null);
            if (original == null) return Outcome.NOT_IN_PARTY;
            if (actor.equals(target)) return Outcome.CANNOT_TARGET_SELF;
            PartyRole role = original.role(target).orElse(null);
            if (role == null) return Outcome.TARGET_NOT_IN_PARTY;
            Party party;
            if (role == PartyRole.MEMBER) {
                if (!can(original, actor, Right.PROMOTE)) return Outcome.NO_PERMISSION;
                party = original.withRole(target, PartyRole.MODERATOR);
                out.add(notice(party.memberIds(), "role.promoted", Map.of("player", actor, "target", target)));
            } else if (role == PartyRole.MODERATOR) {
                if (!original.isLeader(actor)) return Outcome.NOT_LEADER;
                party = original.withLeader(target);
                out.add(notice(party.memberIds(), "leader.promoted", Map.of("player", actor, "target", target)));
            } else {
                return Outcome.TARGET_OUTRANKS;
            }
            commit(original, party, out);
            return Outcome.SUCCESS;
        });
    }

    /** (Owner only) Turns moderator {@code target} back into a plain member. */
    public Outcome demote(UUID owner, UUID target) {
        return write(out -> {
            Party original = store.byMember(owner).orElse(null);
            Outcome denied = checkLeader(original, owner);
            if (denied != null) return denied;
            if (owner.equals(target)) return Outcome.CANNOT_TARGET_SELF;
            PartyRole role = original.role(target).orElse(null);
            if (role == null) return Outcome.TARGET_NOT_IN_PARTY;
            if (role != PartyRole.MODERATOR) return Outcome.NOT_MODERATOR;
            Party party = original.withRole(target, PartyRole.MEMBER);
            out.add(notice(party.memberIds(), "role.demoted", Map.of("player", owner, "target", target)));
            commit(original, party, out);
            return Outcome.SUCCESS;
        });
    }

    /** (Owner only) Destroys the party. */
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
     * Pulls every other online member to {@code server} right away (owner, allowed moderators, or anyone when the
     * party allows members to warp).
     *
     * @param server the actor's current server
     */
    public Outcome warp(UUID actor, String server) {
        Party party = store.byMember(actor).orElse(null);
        if (party == null) return Outcome.NOT_IN_PARTY;
        if (!can(party, actor, Right.WARP)) return Outcome.NO_PERMISSION;
        List<UUID> members = othersOnline(party, actor);
        if (members.isEmpty()) return Outcome.NO_ONE_TO_WARP;
        publish(List.of(
                new Warp(Set.copyOf(members), server, 0),
                notice(members, "warp.summoned", Map.of("player", actor), Map.of("server", server)),
                notice(Set.of(actor), "warp.sent", Map.of(),
                        Map.of("server", server, "count", Integer.toString(members.size())))));
        return Outcome.SUCCESS;
    }

    /**
     * Auto-warp hook: call after a player finished switching servers. If they may start games (owner, or moderator
     * as configured), the party has auto-warp on and the server is a game server, the online members ranked below
     * them follow after {@link PartyRules#warpDelay()}. Pulling only downwards keeps two starters entering different
     * games from warping each other back and forth, and moderators from dragging the owner out of a game.
     *
     * @param party the player's party, as just read by the caller (saves a lookup on every server switch)
     */
    public void switchedServer(UUID player, Party party, String server) {
        if (!party.settings().autoWarp() || !rules.isGameServer(server)) return;
        PartyRole role = party.role(player).orElse(null);
        if (role == null || !rules.grants(role, Right.START_GAMES)) return;
        List<UUID> members = othersOnline(party, player).stream()
                .filter(id -> role.outranks(party.role(id).orElse(PartyRole.OWNER))).toList();
        if (members.isEmpty()) return;
        publish(List.of(
                new Warp(Set.copyOf(members), server, rules.warpDelay().toMillis()),
                notice(members, "warp.following", Map.of("player", player), Map.of("server", server))));
    }

    /** Flips {@code toggle}: {@link Toggle#MUTE} needs {@link Right#MODERATE_CHAT}, the rest {@link Right#SETTINGS}. */
    public Outcome toggle(UUID actor, Toggle toggle) {
        Right right = toggle == Toggle.MUTE ? Right.MODERATE_CHAT : Right.SETTINGS;
        return configure(actor, right, (party, out) -> {
            boolean value = !party.settings().get(toggle);
            out.add(notice(party.memberIds(), value ? "settings.enabled" : "settings.disabled",
                    Map.of("player", actor), Map.of("setting", toggle.key())));
            return party.settings().with(toggle, value);
        });
    }

    /**
     * Caps the party at {@code size} members, up to what the owner's permissions allow.
     *
     * @param size {@code 0} resets the cap to the owner's permission limit
     */
    public Outcome setMaxSize(UUID actor, int size) {
        return configure(actor, Right.SETTINGS, (party, out) -> {
            int limit = sizeLimit(party);
            if (size != 0 && (size < 2 || size > limit)) {
                out.add(notice(Set.of(actor), "error.max-size-range", Map.of(), Map.of("max", Integer.toString(limit))));
                return null;
            }
            out.add(notice(party.memberIds(), "settings.max-size", Map.of("player", actor),
                    Map.of("max", Integer.toString(size == 0 ? limit : size))));
            return party.settings().withMaxSize(size);
        });
    }

    /** Sets party chat slow mode for plain members; {@code 0} turns it off. */
    public Outcome setSlowMode(UUID actor, int seconds) {
        return configure(actor, Right.MODERATE_CHAT, (party, out) -> {
            if (seconds < 0 || seconds > PartySettings.MAX_SLOW_MODE_SECONDS) {
                out.add(notice(Set.of(actor), "error.slow-mode-range", Map.of(),
                        Map.of("max", Integer.toString(PartySettings.MAX_SLOW_MODE_SECONDS))));
                return null;
            }
            out.add(seconds == 0
                    ? notice(party.memberIds(), "settings.slow-mode-off", Map.of("player", actor))
                    : notice(party.memberIds(), "settings.slow-mode", Map.of("player", actor),
                    Map.of("time", Durations.format(Duration.ofSeconds(seconds)))));
            return party.settings().withSlowMode(seconds);
        });
    }

    /** Computes new settings (or {@code null} for an invalid value, after telling the actor) from the locked party. */
    private interface SettingsChange {
        PartySettings apply(Party party, List<PartyEvent> out);
    }

    private Outcome configure(UUID actor, Right right, SettingsChange change) {
        return write(out -> {
            Party original = store.byMember(actor).orElse(null);
            if (original == null) return Outcome.NOT_IN_PARTY;
            if (!can(original, actor, right)) return Outcome.NO_PERMISSION;
            PartySettings settings = change.apply(original, out);
            if (settings == null) return Outcome.INVALID_VALUE;
            store.save(original.withSettings(settings)); // members unchanged: no lonely-party rule, no backend snapshot
            return Outcome.SUCCESS;
        });
    }

    /**
     * Sends a party chat line, honouring mutes, the party's chat switches and slow mode.
     *
     * @param mutedUntil epoch millis until which the sender is muted ({@code 0}: not muted,
     *                   {@link Long#MAX_VALUE}: permanently). Muted senders are blocked and their party
     *                   receives a "currently muted" notice, at most once per
     *                   {@link PartyRules#mutedNoticeCooldown()}.
     * @param mentions   players tagged with {@code @name}, resolved only once the line is allowed (it may cost name
     *                   lookups); only other members of the party are kept
     */
    public Outcome chat(UUID sender, String message, long mutedUntil, Supplier<Set<UUID>> mentions) {
        Party party = store.byMember(sender).orElse(null);
        if (party == null) return Outcome.NOT_IN_PARTY;
        long now = clock.millis();
        if (mutedUntil > now) {
            long cooldown = rules.mutedNoticeCooldown().toMillis();
            Long last = lastMutedNotice.get(sender);
            if (last == null || now - last >= cooldown) {
                lastMutedNotice.put(sender, now);
                publish(List.of(notice(others(party, sender), "chat.muted-notice", Map.of("player", sender))));
            }
            return Outcome.MUTED;
        }
        Outcome refused = checkChat(party, sender, now);
        if (refused != null) return refused;
        if (guard.watches(Action.CHAT, sender)) {
            if (!guard.allows(Action.CHAT, sender, party, message)) return Outcome.CANCELLED;
            party = store.byMember(sender).orElse(null); // the check can take a second: members may have changed
            if (party == null) return Outcome.NOT_IN_PARTY;
        }
        lastChat.put(sender, now);
        Party members = party;
        Set<UUID> tagged = Set.copyOf(mentions.get().stream()
                .filter(id -> !id.equals(sender) && members.isMember(id)).toList());
        publish(List.of(new Chat(party.memberIds(), sender, party.leader(), message, tagged, now)));
        return Outcome.SUCCESS;
    }

    /** @return why {@code sender} may not talk right now, or {@code null} if they may */
    private Outcome checkChat(Party party, UUID sender, long now) {
        PartySettings settings = party.settings();
        if (!settings.chat()) return Outcome.CHAT_DISABLED;
        if (party.role(sender).orElse(PartyRole.MEMBER) != PartyRole.MEMBER) return null; // staff skip mute and slow mode
        if (settings.muted()) return Outcome.CHAT_MUTED;
        if (settings.slowModeSeconds() == 0) return null;
        Long last = lastChat.get(sender);
        long wait = last == null ? 0 : last + settings.slowModeSeconds() * 1000L - now;
        if (wait <= 0) return null;
        // Rounded up: "wait 1s" rather than "wait 0s" with a few hundred ms left.
        publish(List.of(notice(Set.of(sender), "error.slow-mode", Map.of(),
                Map.of("time", Durations.format(Duration.ofSeconds((wait + 999) / 1000))))));
        return Outcome.SLOW_MODE;
    }

    /**
     * Marks {@code player} as disconnected; they keep their slot for {@link PartyRules#disconnectGrace()}.
     * Ignored if the player is already back online (e.g. reconnected through another proxy).
     */
    public void disconnected(UUID player) {
        write(out -> {
            if (isOnline.test(player)) return null;
            Party original = store.byMember(player).orElse(null);
            if (original == null || !original.member(player).orElseThrow().online()) return null;
            long dropAt = clock.millis() + rules.disconnectGrace().toMillis();
            Party party = original.updateMember(player, m -> m.disconnected(dropAt));
            String key = party.isLeader(player) ? "leader.disconnected" : "member.disconnected";
            out.add(notice(others(party, player), key, Map.of("player", player),
                    Map.of("time", Durations.format(rules.disconnectGrace()))));
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
        long cooldown = rules.mutedNoticeCooldown().toMillis();
        lastMutedNotice.values().removeIf(sent -> now - sent >= cooldown);
        lastChat.values().removeIf(sent -> now - sent >= PartySettings.MAX_SLOW_MODE_SECONDS * 1000L);
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
     * Removes {@code player}, cancels the invites they sent (accepting goes through the inviter's party, which they
     * just left) and elects a successor if they led the party.
     *
     * @return the remaining party, or {@code null} if nobody is left
     */
    private Party drop(Party party, UUID player, List<PartyEvent> out, String key, Map<String, UUID> placeholders,
                       boolean randomSuccessor) {
        out.add(new PartyChanged(Set.of(player), null, party));
        List<UUID> invited = party.invites().stream().filter(i -> i.inviter().equals(player)).map(Invite::target).toList();
        for (UUID target : invited) out.add(notice(Set.of(target), "invite.expired-target", Map.of("player", player)));
        if (party.size() == 1) return null;
        boolean leaderLeft = party.isLeader(player);
        UUID next = leaderLeft ? successor(party, player, randomSuccessor) : party.leader();
        Party rest = party.withoutMember(player, next);
        for (UUID target : invited) rest = rest.withoutInvite(target);
        out.add(notice(rest.memberIds(), key, placeholders));
        if (leaderLeft) out.add(notice(rest.memberIds(), "leader.changed", Map.of("player", next)));
        return rest;
    }

    /** Online members before offline ones, moderators before plain members. */
    private UUID successor(Party party, UUID leaving, boolean random) {
        List<UUID> online = othersOnline(party, leaving);
        List<UUID> pool = online.isEmpty() ? others(party, leaving) : online;
        List<UUID> moderators = pool.stream().filter(id -> party.role(id).orElse(null) == PartyRole.MODERATOR).toList();
        if (!moderators.isEmpty()) pool = moderators;
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

    /** Checks {@code actor} may use {@code right} on {@code target}, who must rank below them. */
    private Outcome checkTargeting(Party party, UUID actor, UUID target, Right right) {
        if (party == null) return Outcome.NOT_IN_PARTY;
        if (!can(party, actor, right)) return Outcome.NO_PERMISSION;
        if (actor.equals(target)) return Outcome.CANNOT_TARGET_SELF;
        PartyRole targetRole = party.role(target).orElse(null);
        if (targetRole == null) return Outcome.TARGET_NOT_IN_PARTY;
        if (!party.role(actor).orElseThrow().outranks(targetRole)) return Outcome.TARGET_OUTRANKS;
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
