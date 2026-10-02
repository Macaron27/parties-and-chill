package dev.partiesandchill.core.party;

import dev.partiesandchill.core.party.PartyEvent.Chat;
import dev.partiesandchill.core.party.PartyEvent.Notice;
import dev.partiesandchill.core.party.PartyEvent.PartyChanged;
import dev.partiesandchill.core.party.PartyEvent.Warp;
import dev.partiesandchill.core.party.PartyGuard.Action;
import dev.partiesandchill.core.party.PartyManager.Outcome;
import dev.partiesandchill.core.party.PartyRules.Right;
import dev.partiesandchill.core.party.PartyRules.SizePermission;
import dev.partiesandchill.core.party.PartySettings.Toggle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Party rules, run against every {@link PartyStore} implementation. */
abstract class PartyManagerContract {

    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    static final UUID DAVE = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    final MutableClock clock = new MutableClock();
    final List<PartyEvent> events = new CopyOnWriteArrayList<>();
    final Set<UUID> onlineElsewhere = ConcurrentHashMap.newKeySet();
    final RecordingGuard guard = new RecordingGuard();
    /** Party size each player's permissions grant (absent: none, so {@code max-size}). */
    final Map<UUID, Integer> sizeLimits = new ConcurrentHashMap<>();
    PartyRules rules = rules(EnumSet.of(Right.INVITE, Right.KICK, Right.WARP, Right.START_GAMES, Right.MODERATE_CHAT));
    PartyStore store;
    PartyManager manager;

    static PartyRules rules(Set<Right> moderatorRights) {
        return new PartyRules(4, Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofSeconds(1), List.of("bw-*"),
                Duration.ofSeconds(30), List.of(new SizePermission("parties.size.6", 6)), moderatorRights,
                PartySettings.DEFAULTS);
    }

    /** @return an empty store */
    abstract PartyStore newStore();

    @BeforeEach
    void setUp() {
        store = newStore();
        manager = newManager(rules);
    }

    PartyManager newManager(PartyRules rules) {
        return new PartyManager(store, rules, clock, events::add, onlineElsewhere::contains,
                id -> sizeLimits.getOrDefault(id, 0), new Random(42), guard);
    }

    // --- invites --------------------------------------------------------------------------------------

    @Test
    void inviteCreatesPartyAndNotifiesTarget() {
        assertEquals(Outcome.SUCCESS, manager.invite(ALICE, BOB));

        Party party = manager.partyOf(ALICE).orElseThrow();
        assertEquals(ALICE, party.leader());
        assertTrue(party.inviteFor(BOB, clock.millis()).isPresent());
        assertTrue(manager.partyOf(BOB).isEmpty(), "invitee is not a member yet");
        assertNotice("invite.received", BOB);
        assertNotice("invite.sent", ALICE);
    }

    @Test
    void acceptJoinsTheParty() {
        manager.invite(ALICE, BOB);
        assertEquals(Outcome.SUCCESS, manager.accept(BOB, ALICE));

        Party party = manager.partyOf(BOB).orElseThrow();
        assertEquals(List.of(ALICE, BOB), List.copyOf(party.memberIds()));
        assertTrue(party.invites().isEmpty());
        assertNotice("member.joined", ALICE);
        assertNotice("party.joined", BOB);
        assertTrue(events.stream().anyMatch(e -> e instanceof PartyChanged c && c.party() != null
                && c.affected().equals(Set.of(ALICE, BOB))), "backends get the new roster");
    }

    @Test
    void inviteRules() {
        assertEquals(Outcome.CANNOT_TARGET_SELF, manager.invite(ALICE, ALICE));
        join(ALICE, BOB);
        assertEquals(Outcome.NO_PERMISSION, manager.invite(BOB, CAROL), "plain members can't invite by default");
        assertEquals(Outcome.TARGET_IN_PARTY, manager.invite(CAROL, BOB));
        assertEquals(Outcome.SUCCESS, manager.invite(ALICE, CAROL));
        assertEquals(Outcome.ALREADY_INVITED, manager.invite(ALICE, CAROL));
        assertEquals(Outcome.NO_INVITE, manager.accept(DAVE, ALICE));
    }

    @Test
    void cannotAcceptWhileAlreadyInAnotherParty() {
        UUID erin = UUID.randomUUID();
        manager.invite(ALICE, DAVE);
        join(erin, DAVE);
        assertEquals(Outcome.ALREADY_IN_PARTY, manager.accept(DAVE, ALICE));
        assertEquals(erin, manager.partyOf(DAVE).orElseThrow().leader());
    }

    @Test
    void partyCannotExceedMaxSize() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        join(ALICE, DAVE);
        assertEquals(Outcome.PARTY_FULL, manager.invite(ALICE, UUID.randomUUID()));
    }

    @Test
    void inviteExpiresAfterTtlAndLonelyPartyDisbands() {
        manager.invite(ALICE, BOB);
        clock.advance(Duration.ofSeconds(59));
        manager.tick();
        assertTrue(manager.partyOf(ALICE).isPresent(), "still valid at 59 s");

        clock.advance(Duration.ofSeconds(1));
        manager.tick();
        assertNotice("invite.expired-target", BOB);
        assertNotice("invite.expired", ALICE);
        assertNotice("disband.empty", ALICE);
        assertTrue(manager.partyOf(ALICE).isEmpty());
        assertEquals(Outcome.NO_INVITE, manager.accept(BOB, ALICE));
    }

    @Test
    void sweepIndexOnlyReportsPartiesWithSomethingDue() {
        join(ALICE, BOB); // idle party: nothing pending
        assertFalse(store.anyDue(Long.MAX_VALUE - 1));
        assertTrue(store.due(Long.MAX_VALUE - 1).isEmpty());

        manager.invite(ALICE, CAROL);
        long expiry = clock.millis() + rules.inviteTtl().toMillis();
        assertFalse(store.anyDue(expiry - 1));
        assertTrue(store.anyDue(expiry));
        assertEquals(1, store.due(expiry).size());

        manager.accept(CAROL, ALICE);
        assertFalse(store.anyDue(Long.MAX_VALUE - 1), "accepting clears the deadline");
        manager.disconnected(BOB);
        assertTrue(store.anyDue(clock.millis() + rules.disconnectGrace().toMillis()));
        manager.disband(ALICE);
        assertFalse(store.anyDue(Long.MAX_VALUE - 1), "deleting clears the deadline");
    }

    @Test
    void reconnectOfPlayersWithoutPartyIsANoOp() {
        manager.reconnected(DAVE);
        join(ALICE, BOB);
        events.clear();
        manager.reconnected(BOB); // online already
        assertTrue(events.isEmpty(), () -> "unexpected " + events);
    }

    @Test
    void denyRemovesInviteAndDisbandsLonelyParty() {
        manager.invite(ALICE, BOB);
        assertEquals(Outcome.SUCCESS, manager.deny(BOB, ALICE));
        assertNotice("invite.denied", ALICE);
        assertNotice("invite.denied-self", BOB);
        assertTrue(manager.partyOf(ALICE).isEmpty());
    }

    // --- membership -----------------------------------------------------------------------------------

    @Test
    void leavingLeaderHandsOverToOldestOnlineMember() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        assertEquals(Outcome.SUCCESS, manager.leave(ALICE));

        Party party = manager.partyOf(BOB).orElseThrow();
        assertEquals(BOB, party.leader());
        assertTrue(manager.partyOf(ALICE).isEmpty());
        assertNotice("leader.changed", CAROL);
    }

    @Test
    void partyOfOneIsDisbanded() {
        join(ALICE, BOB);
        manager.leave(BOB);
        assertNotice("member.left", ALICE);
        assertNotice("disband.empty", ALICE);
        assertTrue(manager.partyOf(ALICE).isEmpty());
        assertEquals(Outcome.NOT_IN_PARTY, manager.leave(BOB));
    }

    @Test
    void ownerKicksAnyoneMembersNobody() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        assertEquals(Outcome.NO_PERMISSION, manager.kick(BOB, CAROL));
        assertEquals(Outcome.CANNOT_TARGET_SELF, manager.kick(ALICE, ALICE));
        assertEquals(Outcome.TARGET_NOT_IN_PARTY, manager.kick(ALICE, DAVE));

        assertEquals(Outcome.SUCCESS, manager.kick(ALICE, CAROL));
        assertNotice("party.kicked", CAROL);
        assertTrue(manager.partyOf(CAROL).isEmpty(), "member index updated");
    }

    // --- roles ----------------------------------------------------------------------------------------

    @Test
    void promoteGoesMemberModeratorOwnerAndDemoteBack() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        assertEquals(PartyRole.OWNER, role(ALICE));
        assertEquals(PartyRole.MEMBER, role(BOB));

        assertEquals(Outcome.SUCCESS, manager.promote(ALICE, BOB));
        assertEquals(PartyRole.MODERATOR, role(BOB));
        assertNotice("role.promoted", CAROL);
        assertEquals(Outcome.NO_PERMISSION, manager.promote(BOB, CAROL), "moderators can't promote by default");
        assertEquals(Outcome.NOT_LEADER, manager.demote(BOB, BOB));

        assertEquals(Outcome.SUCCESS, manager.demote(ALICE, BOB));
        assertEquals(PartyRole.MEMBER, role(BOB));
        assertNotice("role.demoted", BOB);
        assertEquals(Outcome.NOT_MODERATOR, manager.demote(ALICE, BOB));

        manager.promote(ALICE, BOB);
        assertEquals(Outcome.SUCCESS, manager.promote(ALICE, BOB), "a moderator promoted again becomes owner");
        Party party = manager.partyOf(ALICE).orElseThrow();
        assertEquals(BOB, party.leader());
        assertEquals(PartyRole.OWNER, role(BOB));
        assertEquals(PartyRole.MODERATOR, role(ALICE), "the previous owner stays on as moderator");
        assertNotice("leader.promoted", CAROL);
        assertTrue(events.stream().anyMatch(e -> e instanceof PartyChanged c && c.party() != null && BOB.equals(c.party().leader())),
                "backends learn the new leader");
        assertEquals(Outcome.NOT_LEADER, manager.disband(ALICE));
        assertEquals(Outcome.TARGET_OUTRANKS, manager.promote(ALICE, BOB));
        assertEquals(Outcome.NO_PERMISSION, manager.promote(ALICE, CAROL), "now a moderator without the promote right");
        assertEquals(Outcome.CANNOT_TARGET_SELF, manager.promote(BOB, BOB));
    }

    @Test
    void moderatorsKickOnlyPlainMembers() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        join(ALICE, DAVE);
        manager.promote(ALICE, BOB);
        manager.promote(ALICE, CAROL);
        assertEquals(Outcome.TARGET_OUTRANKS, manager.kick(BOB, CAROL), "moderator vs moderator");
        assertEquals(Outcome.TARGET_OUTRANKS, manager.kick(BOB, ALICE), "moderator vs owner");
        assertEquals(Outcome.SUCCESS, manager.kick(BOB, DAVE));
        assertNotice("member.kicked", ALICE);
        assertEquals(Outcome.SUCCESS, manager.kick(ALICE, CAROL), "the owner outranks moderators");
    }

    @Test
    void moderatorRightsComeFromTheConfig() {
        manager = newManager(rules(EnumSet.of(Right.PROMOTE, Right.SETTINGS)));
        join(ALICE, BOB);
        join(ALICE, CAROL);
        manager.promote(ALICE, BOB);
        assertEquals(Outcome.NO_PERMISSION, manager.invite(BOB, DAVE));
        assertEquals(Outcome.NO_PERMISSION, manager.kick(BOB, CAROL));
        assertEquals(Outcome.NO_PERMISSION, manager.warp(BOB, "lobby"));
        assertEquals(Outcome.NO_PERMISSION, manager.toggle(BOB, Toggle.MUTE), "no chat-moderation");
        assertEquals(Outcome.SUCCESS, manager.toggle(BOB, Toggle.PUBLIC));
        assertEquals(Outcome.SUCCESS, manager.promote(BOB, CAROL));
        assertEquals(PartyRole.MODERATOR, role(CAROL));
        assertEquals(Outcome.NOT_LEADER, manager.promote(BOB, CAROL), "only the owner hands the party over");
    }

    @Test
    void leavingOwnerHandsOverToAModeratorFirst() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        manager.promote(ALICE, CAROL);
        manager.leave(ALICE);
        assertEquals(CAROL, manager.partyOf(BOB).orElseThrow().leader(), "moderator before the older member");
    }

    @Test
    void disbandNotifiesEveryoneAndClearsBackends() {
        join(ALICE, BOB);
        events.clear();
        assertEquals(Outcome.SUCCESS, manager.disband(ALICE));
        assertNotice("disband.leader", BOB);
        assertTrue(manager.partyOf(BOB).isEmpty());
        assertLeft(Set.of(ALICE, BOB), List.of(ALICE, BOB));
    }

    // --- warping --------------------------------------------------------------------------------------

    @Test
    void warpPullsOnlineMembersRightAway() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        manager.disconnected(CAROL);
        assertEquals(Outcome.NO_PERMISSION, manager.warp(BOB, "lobby-1"));
        assertEquals(Outcome.SUCCESS, manager.warp(ALICE, "lobby-1"));
        assertTrue(events.contains(new Warp(Set.of(BOB), "lobby-1", 0)), "offline members are not warped");
    }

    @Test
    void autoWarpFollowsLeaderIntoGameServersOnly() {
        join(ALICE, BOB);
        switched(ALICE, "lobby-2");
        switched(BOB, "bw-3");
        assertTrue(events.stream().noneMatch(e -> e instanceof Warp), "lobbies and plain members don't warp");

        switched(ALICE, "BW-3");
        assertTrue(events.contains(new Warp(Set.of(BOB), "BW-3", 1000)));
        assertNotice("warp.following", BOB);
    }

    @Test
    void moderatorsStartGamesAndPartiesCanTurnAutoWarpOff() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        join(ALICE, DAVE);
        manager.promote(ALICE, BOB);
        manager.promote(ALICE, DAVE);
        switched(BOB, "bw-1");
        assertTrue(events.contains(new Warp(Set.of(CAROL), "bw-1", 1000)),
                "start-games pulls plain members only, never the owner or another moderator: " + events);
        events.clear();
        switched(ALICE, "bw-1");
        assertTrue(events.contains(new Warp(Set.of(BOB, CAROL, DAVE), "bw-1", 1000)), "the owner pulls everyone");

        events.clear();
        assertEquals(Outcome.SUCCESS, manager.toggle(ALICE, Toggle.AUTO_WARP));
        assertNotice("settings.disabled", CAROL);
        switched(ALICE, "bw-2");
        assertTrue(events.stream().noneMatch(e -> e instanceof Warp), "auto-warp off for this party");
    }

    void switched(UUID player, String server) {
        manager.partyOf(player).ifPresent(party -> manager.switchedServer(player, party, server));
    }

    // --- settings -------------------------------------------------------------------------------------

    @Test
    void newPartiesStartFromTheConfiguredDefaultsAndKeepTheirSettings() {
        PartySettings open = PartySettings.DEFAULTS.with(Toggle.PUBLIC, true).with(Toggle.ALL_INVITE, true);
        manager = newManager(new PartyRules(4, rules.inviteTtl(), rules.disconnectGrace(), rules.warpDelay(),
                rules.gameServers(), rules.mutedNoticeCooldown(), rules.sizePermissions(), rules.moderatorRights(), open));
        join(ALICE, BOB);
        assertEquals(open, manager.partyOf(BOB).orElseThrow().settings());

        assertEquals(Outcome.NO_PERMISSION, manager.toggle(BOB, Toggle.PUBLIC), "members don't change settings");
        assertEquals(Outcome.SUCCESS, manager.toggle(ALICE, Toggle.PUBLIC));
        assertEquals(Outcome.SUCCESS, manager.setMaxSize(ALICE, 3));
        assertEquals(PartySettings.DEFAULTS.with(Toggle.ALL_INVITE, true).withMaxSize(3),
                manager.partyOf(BOB).orElseThrow().settings(), "stored with the party");
        assertNotice("settings.max-size", BOB);
        assertTrue(events.stream().noneMatch(e -> e instanceof PartyChanged c && c.party() != null
                && c.party().settings().maxSize() == 3), "settings don't concern backends");
        assertEquals(Outcome.NOT_IN_PARTY, manager.toggle(DAVE, Toggle.CHAT));
    }

    @Test
    void membersInviteAndWarpWhenThePartyAllowsIt() {
        join(ALICE, BOB);
        assertEquals(Outcome.NO_PERMISSION, manager.invite(BOB, CAROL));
        manager.toggle(ALICE, Toggle.ALL_INVITE);
        assertEquals(Outcome.SUCCESS, manager.invite(BOB, CAROL));
        assertEquals(Outcome.SUCCESS, manager.accept(CAROL, BOB), "an invite from any member counts");

        assertEquals(Outcome.NO_PERMISSION, manager.warp(BOB, "lobby"));
        manager.toggle(ALICE, Toggle.ALL_WARP);
        assertEquals(Outcome.SUCCESS, manager.warp(BOB, "lobby"));
        assertTrue(events.contains(new Warp(Set.of(ALICE, CAROL), "lobby", 0)));
    }

    @Test
    void invitesOfAMemberWhoLeavesAreCancelled() {
        join(ALICE, BOB);
        manager.toggle(ALICE, Toggle.ALL_INVITE);
        manager.invite(BOB, CAROL);
        manager.invite(ALICE, DAVE);
        manager.leave(BOB);
        assertNotice("invite.expired-target", CAROL);
        Party party = manager.partyOf(ALICE).orElseThrow();
        assertTrue(party.inviteFor(CAROL, clock.millis()).isEmpty(), "nobody could accept it through Bob any more");
        assertTrue(party.inviteFor(DAVE, clock.millis()).isPresent(), "other members' invites stay");
        assertEquals(Outcome.SUCCESS, manager.accept(DAVE, ALICE));
    }

    @Test
    void publicPartiesCanBeJoinedWithoutAnInvite() {
        join(ALICE, BOB);
        assertEquals(Outcome.NOT_PUBLIC, manager.join(CAROL, BOB));
        assertEquals(Outcome.NOT_PUBLIC, manager.join(CAROL, DAVE), "no party at all");
        manager.toggle(ALICE, Toggle.PUBLIC);
        assertEquals(Outcome.SUCCESS, manager.join(CAROL, BOB));
        assertNotice("member.joined", ALICE);
        assertNotice("party.joined", CAROL);
        assertEquals(Outcome.ALREADY_IN_PARTY, manager.join(CAROL, ALICE));
        assertEquals(Outcome.CANNOT_TARGET_SELF, manager.join(DAVE, DAVE));

        manager.toggle(ALICE, Toggle.PUBLIC);
        manager.invite(ALICE, DAVE);
        assertEquals(Outcome.SUCCESS, manager.join(DAVE, BOB), "an invite works on a private party");
        assertEquals(Outcome.NOT_PUBLIC, manager.join(UUID.randomUUID(), ALICE), "private again");
    }

    @Test
    void publicJoinsCanBeCancelledByBackendPlugins() {
        join(ALICE, BOB);
        manager.toggle(ALICE, Toggle.PUBLIC);
        guard.cancelled.add(Action.JOIN);
        assertEquals(Outcome.CANCELLED, manager.join(CAROL, ALICE));
        assertTrue(manager.partyOf(CAROL).isEmpty());
        assertEquals(new RecordingGuard.Check(Action.JOIN, CAROL, List.of(ALICE, BOB), ""), guard.last().withoutParty());
    }

    @Test
    void partySizeFollowsTheOwnersPermissions() {
        sizeLimits.put(ALICE, 6);
        for (UUID member : List.of(BOB, CAROL, DAVE)) join(ALICE, member);
        UUID erin = UUID.randomUUID(), frank = UUID.randomUUID();
        join(ALICE, erin);
        join(ALICE, frank);
        assertEquals(6, manager.maxSize(manager.partyOf(ALICE).orElseThrow()));
        assertEquals(Outcome.PARTY_FULL, manager.invite(ALICE, UUID.randomUUID()));

        manager.leave(frank);
        assertEquals(Outcome.INVALID_VALUE, manager.setMaxSize(ALICE, 7), "above the owner's permission limit");
        assertEquals(Map.of("max", "6"), notice("error.max-size-range").values());
        assertEquals(Outcome.INVALID_VALUE, manager.setMaxSize(ALICE, 1));
        assertEquals(Outcome.SUCCESS, manager.setMaxSize(ALICE, 5));
        assertEquals(Outcome.PARTY_FULL, manager.invite(ALICE, frank), "the owner's own cap");
        assertEquals(Outcome.SUCCESS, manager.setMaxSize(ALICE, 0));
        assertEquals(Map.of("max", "6"), notice("settings.max-size").values(), "0 = back to the permission limit");

        sizeLimits.put(ALICE, 1);
        assertEquals(4, manager.maxSize(manager.partyOf(ALICE).orElseThrow()), "never below party.max-size");
    }

    // --- disconnects ----------------------------------------------------------------------------------

    @Test
    void disconnectedMemberKeepsSlotForGraceThenIsDropped() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        manager.disconnected(BOB);
        assertNotice("member.disconnected", ALICE);
        assertFalse(manager.partyOf(BOB).orElseThrow().member(BOB).orElseThrow().online());

        clock.advance(Duration.ofMinutes(4));
        manager.tick();
        assertTrue(manager.partyOf(BOB).isPresent(), "still in grace period");

        clock.advance(Duration.ofMinutes(1));
        manager.tick();
        assertTrue(manager.partyOf(BOB).isEmpty());
        assertNotice("member.timed-out", ALICE);
        assertEquals(List.of(ALICE, CAROL), List.copyOf(manager.partyOf(ALICE).orElseThrow().memberIds()));
    }

    @Test
    void reconnectWithinGraceRestoresMember() {
        join(ALICE, BOB);
        manager.disconnected(BOB);
        clock.advance(Duration.ofMinutes(3));
        manager.reconnected(BOB);
        assertNotice("member.reconnected", ALICE);

        clock.advance(Duration.ofMinutes(10));
        manager.tick();
        assertTrue(manager.partyOf(BOB).orElseThrow().member(BOB).orElseThrow().online());
    }

    @Test
    void leaderTimeoutElectsRandomOnlineMember() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        join(ALICE, DAVE);
        manager.disconnected(DAVE);
        manager.disconnected(ALICE);
        assertNotice("leader.disconnected", BOB);
        clock.advance(Duration.ofMinutes(2));
        manager.tick();
        assertEquals(ALICE, manager.partyOf(BOB).orElseThrow().leader(), "no election during the grace period");

        clock.advance(Duration.ofMinutes(3));
        manager.tick();
        Party party = manager.partyOf(BOB).orElseThrow();
        assertFalse(party.isMember(ALICE));
        assertTrue(Set.of(BOB, CAROL).contains(party.leader()), "picked among online members, got " + party.leader());
        assertNotice("leader.changed", BOB);
    }

    @Test
    void staleDisconnectIsIgnoredWhenPlayerIsOnlineElsewhere() {
        join(ALICE, BOB);
        onlineElsewhere.add(BOB); // already reconnected through another proxy
        manager.disconnected(BOB);
        assertTrue(manager.partyOf(BOB).orElseThrow().member(BOB).orElseThrow().online());
    }

    @Test
    void tickHealsMembersThatAreActuallyOnline() {
        join(ALICE, BOB);
        manager.disconnected(BOB);
        onlineElsewhere.add(BOB);
        clock.advance(Duration.ofMinutes(6));
        manager.tick();
        assertTrue(manager.partyOf(BOB).orElseThrow().member(BOB).orElseThrow().online());
    }

    // --- chat -----------------------------------------------------------------------------------------

    @Test
    void chatReachesTheWholeParty() {
        assertEquals(Outcome.NOT_IN_PARTY, manager.chat(ALICE, "hi", 0, Set::of));
        join(ALICE, BOB);
        assertEquals(Outcome.SUCCESS, manager.chat(BOB, "<red>hi", 0, Set::of));
        Chat chat = lastChat();
        assertEquals(Set.of(ALICE, BOB), chat.recipients());
        assertEquals("<red>hi", chat.message(), "raw text is kept; the renderer never parses it");
        assertEquals(BOB, chat.sender());
        assertEquals(ALICE, chat.owner());
        assertEquals(clock.millis(), chat.sentAt());
    }

    @Test
    void mentionsKeepOnlyOtherPartyMembers() {
        join(ALICE, BOB);
        manager.chat(BOB, "@Alice @Bob @Dave go", 0, () -> Set.of(ALICE, BOB, DAVE));
        assertEquals(Set.of(ALICE), lastChat().mentioned(), "not the sender, not outsiders");
    }

    @Test
    void mentionsAreOnlyResolvedForLinesThatGoOut() {
        join(ALICE, BOB);
        manager.toggle(ALICE, Toggle.MUTE);
        assertEquals(Outcome.CHAT_MUTED, manager.chat(BOB, "@Alice", 0, () -> {
            throw new AssertionError("refused lines must not look names up");
        }));
        assertEquals(Outcome.NOT_IN_PARTY, manager.chat(DAVE, "@Alice", 0, () -> {
            throw new AssertionError("refused lines must not look names up");
        }));
    }

    @Test
    void partyChatCanBeTurnedOffMutedOrSlowed() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        manager.promote(ALICE, CAROL);

        assertEquals(Outcome.NO_PERMISSION, manager.toggle(BOB, Toggle.MUTE));
        assertEquals(Outcome.SUCCESS, manager.toggle(CAROL, Toggle.MUTE), "moderators moderate chat");
        assertEquals(Outcome.CHAT_MUTED, manager.chat(BOB, "hey", 0, Set::of));
        assertEquals(Outcome.SUCCESS, manager.chat(CAROL, "staff can talk", 0, Set::of));
        manager.toggle(ALICE, Toggle.MUTE);

        assertEquals(Outcome.SUCCESS, manager.setSlowMode(CAROL, 10));
        assertNotice("settings.slow-mode", BOB);
        assertEquals(Outcome.SUCCESS, manager.chat(BOB, "one", 0, Set::of));
        clock.advance(Duration.ofMillis(9_200));
        assertEquals(Outcome.SLOW_MODE, manager.chat(BOB, "two", 0, Set::of));
        assertEquals(Map.of("time", "1s"), notice("error.slow-mode").values(), "rounded up");
        assertEquals(Set.of(BOB), notice("error.slow-mode").recipients());
        assertEquals(Outcome.SUCCESS, manager.chat(CAROL, "staff skip it", 0, Set::of));
        clock.advance(Duration.ofMillis(800));
        assertEquals(Outcome.SUCCESS, manager.chat(BOB, "two", 0, Set::of));
        assertEquals(Outcome.INVALID_VALUE, manager.setSlowMode(ALICE, PartySettings.MAX_SLOW_MODE_SECONDS + 1));
        assertNotice("error.slow-mode-range", ALICE);
        assertEquals(Outcome.SUCCESS, manager.setSlowMode(ALICE, 0));
        assertNotice("settings.slow-mode-off", BOB);

        assertEquals(Outcome.NO_PERMISSION, manager.toggle(CAROL, Toggle.CHAT), "settings is off for moderators");
        assertEquals(Outcome.SUCCESS, manager.toggle(ALICE, Toggle.CHAT));
        assertEquals(Outcome.CHAT_DISABLED, manager.chat(ALICE, "anyone?", 0, Set::of), "off means off, owner included");
    }

    @Test
    void mutedPlayerIsBlockedAndPartyGetsRateLimitedNotice() {
        join(ALICE, BOB);
        long mutedUntil = clock.millis() + Duration.ofHours(1).toMillis();
        assertEquals(Outcome.MUTED, manager.chat(BOB, "hello", mutedUntil, Set::of));
        assertEquals(Outcome.MUTED, manager.chat(BOB, "hello?", mutedUntil, Set::of));
        assertEquals(1, count("chat.muted-notice"), "second attempt inside the cooldown is silent");
        assertEquals(Set.of(ALICE), notice("chat.muted-notice").recipients());
        assertTrue(events.stream().noneMatch(e -> e instanceof Chat));

        clock.advance(Duration.ofSeconds(30));
        manager.chat(BOB, "again", Long.MAX_VALUE, Set::of);
        assertEquals(2, count("chat.muted-notice"));
    }

    // --- developer API --------------------------------------------------------------------------------

    @Test
    void createMakesAPartyOfOneThatLastsUntilItChanges() {
        assertEquals(Outcome.PLAYER_OFFLINE, manager.create(ALICE));
        onlineElsewhere.add(ALICE);
        assertEquals(Outcome.SUCCESS, manager.create(ALICE));

        Party party = manager.partyOf(ALICE).orElseThrow();
        assertEquals(List.of(ALICE), List.copyOf(party.memberIds()));
        assertNotice("party.created", ALICE);
        assertTrue(events.contains(new PartyChanged(Set.of(ALICE), party, null)), "backend gets the snapshot");
        assertEquals(Outcome.ALREADY_IN_PARTY, manager.create(ALICE));
        manager.tick();
        assertTrue(manager.partyOf(ALICE).isPresent(), "nothing is due for a party of one");

        assertEquals(Outcome.SUCCESS, manager.invite(ALICE, BOB));
        assertEquals(party.id(), manager.partyOf(ALICE).orElseThrow().id(), "invites reuse the created party");
        assertEquals(Outcome.SUCCESS, manager.deny(BOB, ALICE));
        assertNotice("disband.empty", ALICE);
        assertTrue(manager.partyOf(ALICE).isEmpty());
    }

    @Test
    void addPutsOnlinePlayersStraightIn() {
        onlineElsewhere.addAll(Set.of(BOB, CAROL, DAVE));
        assertEquals(Outcome.NOT_IN_PARTY, manager.add(ALICE, BOB));
        manager.invite(ALICE, BOB);
        events.clear();

        assertEquals(Outcome.SUCCESS, manager.add(ALICE, BOB));
        Party party = manager.partyOf(BOB).orElseThrow();
        assertEquals(List.of(ALICE, BOB), List.copyOf(party.memberIds()));
        assertTrue(party.invites().isEmpty(), "the pending invite is consumed");
        assertNotice("member.joined", ALICE);
        assertNotice("party.joined", BOB);

        assertEquals(Outcome.CANNOT_TARGET_SELF, manager.add(ALICE, ALICE));
        assertEquals(Outcome.NOT_LEADER, manager.add(BOB, CAROL));
        assertEquals(Outcome.TARGET_IN_PARTY, manager.add(ALICE, BOB));
        assertEquals(Outcome.PLAYER_OFFLINE, manager.add(ALICE, UUID.randomUUID()));
        assertEquals(Outcome.SUCCESS, manager.add(ALICE, CAROL));
        assertEquals(Outcome.SUCCESS, manager.add(ALICE, DAVE));
        UUID erin = UUID.randomUUID();
        onlineElsewhere.add(erin);
        assertEquals(Outcome.PARTY_FULL, manager.add(ALICE, erin));
    }

    @Test
    void backendPluginsCanCancelCreateJoinDisbandAndChat() {
        onlineElsewhere.addAll(Set.of(ALICE, BOB));
        guard.cancelled.add(Action.CREATE);
        assertEquals(Outcome.CANCELLED, manager.invite(ALICE, BOB));
        assertEquals(Outcome.CANCELLED, manager.create(ALICE));
        assertTrue(manager.partyOf(ALICE).isEmpty());
        assertTrue(events.isEmpty(), "a cancelled action has no side effect: " + events);

        guard.cancelled.clear();
        manager.invite(ALICE, BOB);
        RecordingGuard.Check create = guard.last();
        assertEquals(new RecordingGuard.Check(Action.CREATE, ALICE, List.of(ALICE), ""), create.withoutParty());
        assertEquals(manager.partyOf(ALICE).orElseThrow().id(), create.party().id(), "plugins see the real party id");

        guard.cancelled.add(Action.JOIN);
        assertEquals(Outcome.CANCELLED, manager.accept(BOB, ALICE));
        assertEquals(Outcome.CANCELLED, manager.add(ALICE, BOB));
        assertTrue(manager.partyOf(BOB).isEmpty());
        assertTrue(manager.partyOf(ALICE).orElseThrow().inviteFor(BOB, clock.millis()).isPresent(), "invite kept");
        assertEquals(new RecordingGuard.Check(Action.JOIN, BOB, List.of(ALICE), ""), guard.last().withoutParty());

        guard.cancelled.clear();
        manager.accept(BOB, ALICE);
        guard.cancelled.addAll(Set.of(Action.DISBAND, Action.CHAT));
        events.clear();
        assertEquals(Outcome.CANCELLED, manager.disband(ALICE));
        assertEquals(Outcome.CANCELLED, manager.chat(BOB, "gg", 0, Set::of));
        assertEquals(new RecordingGuard.Check(Action.CHAT, BOB, List.of(ALICE, BOB), "gg"), guard.last().withoutParty());
        assertEquals(List.of(ALICE, BOB), List.copyOf(manager.partyOf(ALICE).orElseThrow().memberIds()));
        assertTrue(events.isEmpty(), "no chat line, no disband: " + events);
    }

    @Test
    void chatReachesThePartyAsItIsAfterTheCheck() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        guard.duringCheck = () -> manager.kick(ALICE, CAROL); // while the backend decides
        assertEquals(Outcome.SUCCESS, manager.chat(BOB, "hi", 0, Set::of));
        assertEquals(Set.of(ALICE, BOB), lastChat().recipients());
    }

    @Test
    void failedPreconditionsAndUnwatchedPlayersSkipTheGuard() {
        assertEquals(Outcome.NO_INVITE, manager.accept(BOB, ALICE));
        assertEquals(Outcome.NOT_IN_PARTY, manager.disband(ALICE));
        assertTrue(guard.checks.isEmpty(), "no event for actions that can't happen");

        guard.watching = false;
        guard.cancelled.addAll(Set.of(Action.values()));
        join(ALICE, BOB);
        assertEquals(Outcome.SUCCESS, manager.chat(BOB, "hi", 0, Set::of));
        assertEquals(Outcome.SUCCESS, manager.disband(ALICE));
        assertTrue(guard.checks.isEmpty());
    }

    @Test
    void everyoneWhoLeavesAPartyIsReportedWithIt() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        manager.kick(ALICE, CAROL);
        assertLeft(Set.of(CAROL), List.of(ALICE, BOB, CAROL));
        manager.leave(BOB);
        assertLeft(Set.of(BOB), List.of(ALICE, BOB));
        assertLeft(Set.of(ALICE), List.of(ALICE));
        assertTrue(events.stream().noneMatch(e -> e instanceof PartyChanged c && c.party() != null && c.previous() != null));
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** Asserts a "they left" snapshot for {@code players}, carrying the party as it was ({@code members}). */
    void assertLeft(Set<UUID> players, List<UUID> members) {
        assertTrue(events.stream().anyMatch(e -> e instanceof PartyChanged c && c.party() == null
                        && c.affected().equals(players) && List.copyOf(c.previous().memberIds()).equals(members)),
                () -> "expected " + players + " to leave " + members + " in " + events);
    }

    void join(UUID leader, UUID member) {
        assertEquals(Outcome.SUCCESS, manager.invite(leader, member));
        assertEquals(Outcome.SUCCESS, manager.accept(member, leader));
    }

    Notice notice(String key) {
        return events.stream().filter(e -> e instanceof Notice n && n.key().equals(key)).map(Notice.class::cast)
                .reduce((first, second) -> second).orElseThrow(() -> new AssertionError("no notice " + key + " in " + events));
    }

    Chat lastChat() {
        return events.stream().filter(Chat.class::isInstance).map(Chat.class::cast)
                .reduce((first, second) -> second).orElseThrow(() -> new AssertionError("no chat line in " + events));
    }

    PartyRole role(UUID player) {
        return manager.partyOf(player).orElseThrow().role(player).orElseThrow();
    }

    long count(String key) {
        return events.stream().filter(e -> e instanceof Notice n && n.key().equals(key)).count();
    }

    void assertNotice(String key, UUID recipient) {
        assertTrue(events.stream().anyMatch(e -> e instanceof Notice n && n.key().equals(key) && n.recipients().contains(recipient)),
                () -> "expected notice " + key + " for " + recipient + " in " + events);
    }

    /** Records every check; cancels the actions in {@link #cancelled}. */
    static final class RecordingGuard implements PartyGuard {
        record Check(Action action, UUID player, Party party, List<UUID> members, String message) {
            Check(Action action, UUID player, List<UUID> members, String message) {
                this(action, player, null, members, message);
            }

            Check withoutParty() {
                return new Check(action, player, members, message);
            }
        }

        final List<Check> checks = new CopyOnWriteArrayList<>();
        final Set<Action> cancelled = ConcurrentHashMap.newKeySet();
        volatile boolean watching = true;
        volatile Runnable duringCheck = () -> { };

        @Override
        public boolean watches(Action action, UUID player) {
            return watching;
        }

        @Override
        public boolean allows(Action action, UUID player, Party party, String message) {
            checks.add(new Check(action, player, party, List.copyOf(party.memberIds()), message));
            Runnable during = duringCheck;
            duringCheck = () -> { };
            during.run();
            return !cancelled.contains(action);
        }

        Check last() {
            return checks.getLast();
        }
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
