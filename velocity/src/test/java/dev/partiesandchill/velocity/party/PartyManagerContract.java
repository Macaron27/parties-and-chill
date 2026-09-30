package dev.partiesandchill.velocity.party;

import dev.partiesandchill.velocity.party.PartyEvent.Notice;
import dev.partiesandchill.velocity.party.PartyEvent.PartyChanged;
import dev.partiesandchill.velocity.party.PartyEvent.Warp;
import dev.partiesandchill.velocity.party.PartyGuard.Action;
import dev.partiesandchill.velocity.party.PartyManager.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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
    final PartySettings settings = new PartySettings(4, Duration.ofSeconds(60), Duration.ofMinutes(5),
            Duration.ofSeconds(1), List.of("bw-*"), Duration.ofSeconds(30));
    PartyStore store;
    PartyManager manager;

    /** @return an empty store */
    abstract PartyStore newStore();

    @BeforeEach
    void setUp() {
        store = newStore();
        manager = new PartyManager(store, settings, clock, events::add, onlineElsewhere::contains, new Random(42), guard);
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
        assertEquals(Outcome.NOT_LEADER, manager.invite(BOB, CAROL));
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
        long expiry = clock.millis() + settings.inviteTtl().toMillis();
        assertFalse(store.anyDue(expiry - 1));
        assertTrue(store.anyDue(expiry));
        assertEquals(1, store.due(expiry).size());

        manager.accept(CAROL, ALICE);
        assertFalse(store.anyDue(Long.MAX_VALUE - 1), "accepting clears the deadline");
        manager.disconnected(BOB);
        assertTrue(store.anyDue(clock.millis() + settings.disconnectGrace().toMillis()));
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
    void kickAndPromoteAreLeaderOnly() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        assertEquals(Outcome.NOT_LEADER, manager.kick(BOB, CAROL));
        assertEquals(Outcome.NOT_LEADER, manager.promote(BOB, BOB));
        assertEquals(Outcome.CANNOT_TARGET_SELF, manager.kick(ALICE, ALICE));
        assertEquals(Outcome.TARGET_NOT_IN_PARTY, manager.kick(ALICE, DAVE));

        assertEquals(Outcome.SUCCESS, manager.kick(ALICE, CAROL));
        assertNotice("party.kicked", CAROL);
        assertTrue(manager.partyOf(CAROL).isEmpty(), "member index updated");

        assertEquals(Outcome.SUCCESS, manager.promote(ALICE, BOB));
        assertEquals(BOB, manager.partyOf(ALICE).orElseThrow().leader());
        assertEquals(Outcome.NOT_LEADER, manager.disband(ALICE));
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
        assertEquals(Outcome.NOT_LEADER, manager.warp(BOB, "lobby-1"));
        assertEquals(Outcome.SUCCESS, manager.warp(ALICE, "lobby-1"));
        assertTrue(events.contains(new Warp(Set.of(BOB), "lobby-1", 0)), "offline members are not warped");
    }

    @Test
    void autoWarpFollowsLeaderIntoGameServersOnly() {
        join(ALICE, BOB);
        manager.leaderSwitchedServer(ALICE, "lobby-2");
        manager.leaderSwitchedServer(BOB, "bw-3");
        assertTrue(events.stream().noneMatch(e -> e instanceof Warp), "lobbies and non-leaders don't warp");

        manager.leaderSwitchedServer(ALICE, "BW-3");
        assertTrue(events.contains(new Warp(Set.of(BOB), "BW-3", 1000)));
        assertNotice("warp.following", BOB);
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
        assertEquals(Outcome.NOT_IN_PARTY, manager.chat(ALICE, "hi", 0));
        join(ALICE, BOB);
        assertEquals(Outcome.SUCCESS, manager.chat(BOB, "<red>hi", 0));
        Notice chat = notice("chat.format");
        assertEquals(Set.of(ALICE, BOB), chat.recipients());
        assertEquals("<red>hi", chat.values().get("message"), "raw text is kept; the renderer never parses it");
    }

    @Test
    void mutedPlayerIsBlockedAndPartyGetsRateLimitedNotice() {
        join(ALICE, BOB);
        long mutedUntil = clock.millis() + Duration.ofHours(1).toMillis();
        assertEquals(Outcome.MUTED, manager.chat(BOB, "hello", mutedUntil));
        assertEquals(Outcome.MUTED, manager.chat(BOB, "hello?", mutedUntil));
        assertEquals(1, count("chat.muted-notice"), "second attempt inside the cooldown is silent");
        assertEquals(Set.of(ALICE), notice("chat.muted-notice").recipients());
        assertEquals(0, count("chat.format"));

        clock.advance(Duration.ofSeconds(30));
        manager.chat(BOB, "again", Long.MAX_VALUE);
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
        assertEquals(Outcome.CANCELLED, manager.chat(BOB, "gg", 0));
        assertEquals(new RecordingGuard.Check(Action.CHAT, BOB, List.of(ALICE, BOB), "gg"), guard.last().withoutParty());
        assertEquals(List.of(ALICE, BOB), List.copyOf(manager.partyOf(ALICE).orElseThrow().memberIds()));
        assertTrue(events.isEmpty(), "no chat line, no disband: " + events);
    }

    @Test
    void chatReachesThePartyAsItIsAfterTheCheck() {
        join(ALICE, BOB);
        join(ALICE, CAROL);
        guard.duringCheck = () -> manager.kick(ALICE, CAROL); // while the backend decides
        assertEquals(Outcome.SUCCESS, manager.chat(BOB, "hi", 0));
        assertEquals(Set.of(ALICE, BOB), notice("chat.format").recipients());
    }

    @Test
    void failedPreconditionsAndUnwatchedPlayersSkipTheGuard() {
        assertEquals(Outcome.NO_INVITE, manager.accept(BOB, ALICE));
        assertEquals(Outcome.NOT_IN_PARTY, manager.disband(ALICE));
        assertTrue(guard.checks.isEmpty(), "no event for actions that can't happen");

        guard.watching = false;
        guard.cancelled.addAll(Set.of(Action.values()));
        join(ALICE, BOB);
        assertEquals(Outcome.SUCCESS, manager.chat(BOB, "hi", 0));
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
