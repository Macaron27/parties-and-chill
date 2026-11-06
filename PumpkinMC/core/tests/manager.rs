//! Party rules: the port of Java's `PartyManagerContract`, case by case.

mod common;

use std::sync::atomic::Ordering;
use std::time::Duration;

use common::*;
use parties_core::event::PartyEvent;
use parties_core::manager::{Action, Outcome};
use parties_core::party::{PartyRole, PartySettings, Toggle};
use parties_core::rules::Right;
use uuid::Uuid;

// --- invites ------------------------------------------------------------------------------------------

#[test]
fn invite_creates_party_and_notifies_target() {
    let h = Harness::new();
    assert_eq!(Outcome::Success, h.m().invite(ALICE, BOB));
    let party = h.party(ALICE);
    assert_eq!(ALICE, party.leader);
    assert!(party.invite_for(BOB, h.clock.millis()).is_some());
    assert!(h.m().party_of(BOB).is_none(), "invitee is not a member yet");
    h.assert_notice("invite.received", BOB);
    h.assert_notice("invite.sent", ALICE);
}

#[test]
fn accept_joins_the_party() {
    let h = Harness::new();
    h.m().invite(ALICE, BOB);
    assert_eq!(Outcome::Success, h.m().accept(BOB, ALICE));
    let party = h.party(BOB);
    assert_eq!(vec![ALICE, BOB], party.member_ids());
    assert!(party.invites.is_empty());
    h.assert_notice("member.joined", ALICE);
    h.assert_notice("party.joined", BOB);
    assert!(
        h.events().iter().any(|e| matches!(e, PartyEvent::PartyChanged { party: Some(_), affected, .. } if set(affected) == set(&[ALICE, BOB]))),
        "the new roster is announced"
    );
}

#[test]
fn invite_rules() {
    let h = Harness::new();
    assert_eq!(Outcome::CannotTargetSelf, h.m().invite(ALICE, ALICE));
    h.join(ALICE, BOB);
    assert_eq!(Outcome::NoPermission, h.m().invite(BOB, CAROL), "plain members can't invite by default");
    assert_eq!(Outcome::TargetInParty, h.m().invite(CAROL, BOB));
    assert_eq!(Outcome::Success, h.m().invite(ALICE, CAROL));
    assert_eq!(Outcome::AlreadyInvited, h.m().invite(ALICE, CAROL));
    assert_eq!(Outcome::NoInvite, h.m().accept(DAVE, ALICE));
}

#[test]
fn cannot_accept_while_already_in_another_party() {
    let h = Harness::new();
    let erin = Uuid::new_v4();
    h.m().invite(ALICE, DAVE);
    h.join(erin, DAVE);
    assert_eq!(Outcome::AlreadyInParty, h.m().accept(DAVE, ALICE));
    assert_eq!(erin, h.party(DAVE).leader);
}

#[test]
fn party_cannot_exceed_max_size() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    h.join(ALICE, DAVE);
    assert_eq!(Outcome::PartyFull, h.m().invite(ALICE, Uuid::new_v4()));
}

#[test]
fn invite_expires_after_ttl_and_lonely_party_disbands() {
    let h = Harness::new();
    h.m().invite(ALICE, BOB);
    h.clock.advance(Duration::from_secs(59));
    h.m().tick();
    assert!(h.m().party_of(ALICE).is_some(), "still valid at 59 s");

    h.clock.advance(Duration::from_secs(1));
    h.m().tick();
    h.assert_notice("invite.expired-target", BOB);
    h.assert_notice("invite.expired", ALICE);
    h.assert_notice("disband.empty", ALICE);
    assert!(h.m().party_of(ALICE).is_none());
    assert_eq!(Outcome::NoInvite, h.m().accept(BOB, ALICE));
}

#[test]
fn reconnect_of_players_without_party_is_a_no_op() {
    let h = Harness::new();
    h.m().reconnected(DAVE);
    h.join(ALICE, BOB);
    h.clear();
    h.m().reconnected(BOB); // online already
    assert!(h.events().is_empty(), "unexpected {:?}", h.events());
}

#[test]
fn deny_removes_invite_and_disbands_lonely_party() {
    let h = Harness::new();
    h.m().invite(ALICE, BOB);
    assert_eq!(Outcome::Success, h.m().deny(BOB, ALICE));
    h.assert_notice("invite.denied", ALICE);
    h.assert_notice("invite.denied-self", BOB);
    assert!(h.m().party_of(ALICE).is_none());
}

// --- membership ---------------------------------------------------------------------------------------

#[test]
fn leaving_leader_hands_over_to_oldest_online_member() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    assert_eq!(Outcome::Success, h.m().leave(ALICE));
    assert_eq!(BOB, h.party(BOB).leader);
    assert!(h.m().party_of(ALICE).is_none());
    h.assert_notice("leader.changed", CAROL);
}

#[test]
fn party_of_one_is_disbanded() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.m().leave(BOB);
    h.assert_notice("member.left", ALICE);
    h.assert_notice("disband.empty", ALICE);
    assert!(h.m().party_of(ALICE).is_none());
    assert_eq!(Outcome::NotInParty, h.m().leave(BOB));
}

#[test]
fn owner_kicks_anyone_members_nobody() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    assert_eq!(Outcome::NoPermission, h.m().kick(BOB, CAROL));
    assert_eq!(Outcome::CannotTargetSelf, h.m().kick(ALICE, ALICE));
    assert_eq!(Outcome::TargetNotInParty, h.m().kick(ALICE, DAVE));
    assert_eq!(Outcome::Success, h.m().kick(ALICE, CAROL));
    h.assert_notice("party.kicked", CAROL);
    assert!(h.m().party_of(CAROL).is_none(), "member index updated");
}

// --- roles --------------------------------------------------------------------------------------------

#[test]
fn promote_goes_member_moderator_owner_and_demote_back() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    assert_eq!(PartyRole::Owner, h.role(ALICE));
    assert_eq!(PartyRole::Member, h.role(BOB));

    assert_eq!(Outcome::Success, h.m().promote(ALICE, BOB));
    assert_eq!(PartyRole::Moderator, h.role(BOB));
    h.assert_notice("role.promoted", CAROL);
    assert_eq!(Outcome::NoPermission, h.m().promote(BOB, CAROL), "moderators can't promote by default");
    assert_eq!(Outcome::NotLeader, h.m().demote(BOB, BOB));

    assert_eq!(Outcome::Success, h.m().demote(ALICE, BOB));
    assert_eq!(PartyRole::Member, h.role(BOB));
    h.assert_notice("role.demoted", BOB);
    assert_eq!(Outcome::NotModerator, h.m().demote(ALICE, BOB));

    h.m().promote(ALICE, BOB);
    assert_eq!(Outcome::Success, h.m().promote(ALICE, BOB), "a moderator promoted again becomes owner");
    assert_eq!(BOB, h.party(ALICE).leader);
    assert_eq!(PartyRole::Owner, h.role(BOB));
    assert_eq!(PartyRole::Moderator, h.role(ALICE), "the previous owner stays on as moderator");
    h.assert_notice("leader.promoted", CAROL);
    assert!(
        h.events().iter().any(|e| matches!(e, PartyEvent::PartyChanged { party: Some(p), .. } if p.leader == BOB)),
        "the new leader is announced"
    );
    assert_eq!(Outcome::NotLeader, h.m().disband(ALICE));
    assert_eq!(Outcome::TargetOutranks, h.m().promote(ALICE, BOB));
    assert_eq!(Outcome::NoPermission, h.m().promote(ALICE, CAROL), "now a moderator without the promote right");
    assert_eq!(Outcome::CannotTargetSelf, h.m().promote(BOB, BOB));
}

#[test]
fn moderators_kick_only_plain_members() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    h.join(ALICE, DAVE);
    h.m().promote(ALICE, BOB);
    h.m().promote(ALICE, CAROL);
    assert_eq!(Outcome::TargetOutranks, h.m().kick(BOB, CAROL), "moderator vs moderator");
    assert_eq!(Outcome::TargetOutranks, h.m().kick(BOB, ALICE), "moderator vs owner");
    assert_eq!(Outcome::Success, h.m().kick(BOB, DAVE));
    h.assert_notice("member.kicked", ALICE);
    assert_eq!(Outcome::Success, h.m().kick(ALICE, CAROL), "the owner outranks moderators");
}

#[test]
fn moderator_rights_come_from_the_config() {
    let mut h = Harness::new();
    h.renew(rules(&[Right::Promote, Right::Settings]));
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    h.m().promote(ALICE, BOB);
    assert_eq!(Outcome::NoPermission, h.m().invite(BOB, DAVE));
    assert_eq!(Outcome::NoPermission, h.m().kick(BOB, CAROL));
    assert_eq!(Outcome::NoPermission, h.m().warp(BOB, "lobby"));
    assert_eq!(Outcome::NoPermission, h.m().toggle(BOB, Toggle::Mute), "no chat-moderation");
    assert_eq!(Outcome::Success, h.m().toggle(BOB, Toggle::Public));
    assert_eq!(Outcome::Success, h.m().promote(BOB, CAROL));
    assert_eq!(PartyRole::Moderator, h.role(CAROL));
    assert_eq!(Outcome::NotLeader, h.m().promote(BOB, CAROL), "only the owner hands the party over");
}

#[test]
fn leaving_owner_hands_over_to_a_moderator_first() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    h.m().promote(ALICE, CAROL);
    h.m().leave(ALICE);
    assert_eq!(CAROL, h.party(BOB).leader, "moderator before the older member");
}

#[test]
fn disband_notifies_everyone() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.clear();
    assert_eq!(Outcome::Success, h.m().disband(ALICE));
    h.assert_notice("disband.leader", BOB);
    assert!(h.m().party_of(BOB).is_none());
    h.assert_left(&[ALICE, BOB], &[ALICE, BOB]);
}

// --- warping ------------------------------------------------------------------------------------------

#[test]
fn warp_pulls_online_members_right_away() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    h.m().disconnected(CAROL);
    assert_eq!(Outcome::NoPermission, h.m().warp(BOB, "lobby-1"));
    assert_eq!(Outcome::Success, h.m().warp(ALICE, "lobby-1"));
    assert!(h.warps().contains(&(set(&[BOB]), "lobby-1".to_owned(), 0)), "offline members are not warped");
    h.assert_notice("warp.summoned", BOB);
    assert_eq!(vec![("server", "lobby-1".to_owned()), ("count", "1".to_owned())], h.notice("warp.sent").1);
}

#[test]
fn auto_warp_follows_leader_into_game_servers_only() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    switched(&h, ALICE, "lobby-2");
    switched(&h, BOB, "bw-3");
    assert!(h.warps().is_empty(), "lobbies and plain members don't warp");

    switched(&h, ALICE, "BW-3");
    assert!(h.warps().contains(&(set(&[BOB]), "BW-3".to_owned(), 1000)));
    h.assert_notice("warp.following", BOB);
}

#[test]
fn moderators_start_games_and_parties_can_turn_auto_warp_off() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    h.join(ALICE, DAVE);
    h.m().promote(ALICE, BOB);
    h.m().promote(ALICE, DAVE);
    switched(&h, BOB, "bw-1");
    assert!(
        h.warps().contains(&(set(&[CAROL]), "bw-1".to_owned(), 1000)),
        "start-games pulls plain members only, never the owner or another moderator"
    );
    h.clear();
    switched(&h, ALICE, "bw-1");
    assert!(h.warps().contains(&(set(&[BOB, CAROL, DAVE]), "bw-1".to_owned(), 1000)), "the owner pulls everyone");

    h.clear();
    assert_eq!(Outcome::Success, h.m().toggle(ALICE, Toggle::AutoWarp));
    h.assert_notice("settings.disabled", CAROL);
    switched(&h, ALICE, "bw-2");
    assert!(h.warps().is_empty(), "auto-warp off for this party");
}

fn switched(h: &Harness, player: Uuid, server: &str) {
    if let Some(party) = h.m().party_of(player) {
        h.m().switched_server(player, &party, server);
    }
}

// --- settings -----------------------------------------------------------------------------------------

#[test]
fn new_parties_start_from_the_configured_defaults_and_keep_their_settings() {
    let open = PartySettings::DEFAULTS.with(Toggle::Public, true).with(Toggle::AllInvite, true);
    let h = Harness::with_rules(rules_with(&DEFAULT_RIGHTS, open));
    h.join(ALICE, BOB);
    assert_eq!(open, h.party(BOB).settings);

    assert_eq!(Outcome::NoPermission, h.m().toggle(BOB, Toggle::Public), "members don't change settings");
    assert_eq!(Outcome::Success, h.m().toggle(ALICE, Toggle::Public));
    assert_eq!(Outcome::Success, h.m().set_max_size(ALICE, 3));
    assert_eq!(
        PartySettings::DEFAULTS.with(Toggle::AllInvite, true).with_max_size(3),
        h.party(BOB).settings,
        "stored with the party"
    );
    h.assert_notice("settings.max-size", BOB);
    assert!(
        !h.events()
            .iter()
            .any(|e| matches!(e, PartyEvent::PartyChanged { party: Some(p), .. } if p.settings.max_size == 3)),
        "settings don't change the roster"
    );
    assert_eq!(Outcome::NotInParty, h.m().toggle(DAVE, Toggle::Chat));
}

#[test]
fn members_invite_and_warp_when_the_party_allows_it() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    assert_eq!(Outcome::NoPermission, h.m().invite(BOB, CAROL));
    h.m().toggle(ALICE, Toggle::AllInvite);
    assert_eq!(Outcome::Success, h.m().invite(BOB, CAROL));
    assert_eq!(Outcome::Success, h.m().accept(CAROL, BOB), "an invite from any member counts");

    assert_eq!(Outcome::NoPermission, h.m().warp(BOB, "lobby"));
    h.m().toggle(ALICE, Toggle::AllWarp);
    assert_eq!(Outcome::Success, h.m().warp(BOB, "lobby"));
    assert!(h.warps().contains(&(set(&[ALICE, CAROL]), "lobby".to_owned(), 0)));
}

#[test]
fn invites_of_a_member_who_leaves_are_cancelled() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.m().toggle(ALICE, Toggle::AllInvite);
    h.m().invite(BOB, CAROL);
    h.m().invite(ALICE, DAVE);
    h.m().leave(BOB);
    h.assert_notice("invite.expired-target", CAROL);
    let party = h.party(ALICE);
    assert!(party.invite_for(CAROL, h.clock.millis()).is_none(), "nobody could accept it through Bob any more");
    assert!(party.invite_for(DAVE, h.clock.millis()).is_some(), "other members' invites stay");
    assert_eq!(Outcome::Success, h.m().accept(DAVE, ALICE));
}

#[test]
fn public_parties_can_be_joined_without_an_invite() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    assert_eq!(Outcome::NotPublic, h.m().join(CAROL, BOB));
    assert_eq!(Outcome::NotPublic, h.m().join(CAROL, DAVE), "no party at all");
    h.m().toggle(ALICE, Toggle::Public);
    assert_eq!(Outcome::Success, h.m().join(CAROL, BOB));
    h.assert_notice("member.joined", ALICE);
    h.assert_notice("party.joined", CAROL);
    assert_eq!(Outcome::AlreadyInParty, h.m().join(CAROL, ALICE));
    assert_eq!(Outcome::CannotTargetSelf, h.m().join(DAVE, DAVE));

    h.m().toggle(ALICE, Toggle::Public);
    h.m().invite(ALICE, DAVE);
    assert_eq!(Outcome::Success, h.m().join(DAVE, BOB), "an invite works on a private party");
    assert_eq!(Outcome::NotPublic, h.m().join(Uuid::new_v4(), ALICE), "private again");
}

#[test]
fn public_joins_can_be_cancelled_by_other_plugins() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.m().toggle(ALICE, Toggle::Public);
    h.guard.cancelled.lock().unwrap().insert(Action::Join);
    assert_eq!(Outcome::Cancelled, h.m().join(CAROL, ALICE));
    assert!(h.m().party_of(CAROL).is_none());
    let check = h.guard.last();
    assert_eq!(
        (Action::Join, CAROL, vec![ALICE, BOB], String::new()),
        (check.action, check.player, check.members, check.message)
    );
}

#[test]
fn party_size_follows_the_owners_permissions() {
    let h = Harness::new();
    h.size_limits.lock().unwrap().insert(ALICE, 6);
    for member in [BOB, CAROL, DAVE] {
        h.join(ALICE, member);
    }
    let (erin, frank) = (Uuid::new_v4(), Uuid::new_v4());
    h.join(ALICE, erin);
    h.join(ALICE, frank);
    assert_eq!(6, h.m().max_size(&h.party(ALICE)));
    assert_eq!(Outcome::PartyFull, h.m().invite(ALICE, Uuid::new_v4()));

    h.m().leave(frank);
    assert_eq!(Outcome::InvalidValue, h.m().set_max_size(ALICE, 7), "above the owner's permission limit");
    assert_eq!(vec![("max", "6".to_owned())], h.notice("error.max-size-range").1);
    assert_eq!(Outcome::InvalidValue, h.m().set_max_size(ALICE, 1));
    assert_eq!(Outcome::InvalidValue, h.m().set_max_size(ALICE, -3));
    assert_eq!(Outcome::Success, h.m().set_max_size(ALICE, 5));
    assert_eq!(Outcome::PartyFull, h.m().invite(ALICE, frank), "the owner's own cap");
    assert_eq!(Outcome::Success, h.m().set_max_size(ALICE, 0));
    assert_eq!(vec![("max", "6".to_owned())], h.notice("settings.max-size").1, "0 = back to the permission limit");

    h.size_limits.lock().unwrap().insert(ALICE, 1);
    assert_eq!(4, h.m().max_size(&h.party(ALICE)), "never below party.max-size");
}

// --- disconnects --------------------------------------------------------------------------------------

#[test]
fn disconnected_member_keeps_slot_for_grace_then_is_dropped() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    h.m().disconnected(BOB);
    h.assert_notice("member.disconnected", ALICE);
    assert!(!h.party(BOB).member(BOB).unwrap().online());

    h.clock.advance(Duration::from_secs(240));
    h.m().tick();
    assert!(h.m().party_of(BOB).is_some(), "still in grace period");

    h.clock.advance(Duration::from_secs(60));
    h.m().tick();
    assert!(h.m().party_of(BOB).is_none());
    h.assert_notice("member.timed-out", ALICE);
    assert_eq!(vec![ALICE, CAROL], h.party(ALICE).member_ids());
}

#[test]
fn reconnect_within_grace_restores_member() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.m().disconnected(BOB);
    h.clock.advance(Duration::from_secs(180));
    h.m().reconnected(BOB);
    h.assert_notice("member.reconnected", ALICE);
    h.clock.advance(Duration::from_secs(600));
    h.m().tick();
    assert!(h.party(BOB).member(BOB).unwrap().online());
}

#[test]
fn leader_timeout_elects_random_online_member() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    h.join(ALICE, DAVE);
    h.m().disconnected(DAVE);
    h.m().disconnected(ALICE);
    h.assert_notice("leader.disconnected", BOB);
    h.clock.advance(Duration::from_secs(120));
    h.m().tick();
    assert_eq!(ALICE, h.party(BOB).leader, "no election during the grace period");

    h.clock.advance(Duration::from_secs(180));
    h.m().tick();
    let party = h.party(BOB);
    assert!(!party.is_member(ALICE));
    assert!(set(&[BOB, CAROL]).contains(&party.leader), "picked among online members, got {}", party.leader);
    h.assert_notice("leader.changed", BOB);
}

#[test]
fn stale_disconnect_is_ignored_when_player_is_online() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.set_online(&[BOB]); // already back
    h.m().disconnected(BOB);
    assert!(h.party(BOB).member(BOB).unwrap().online());
}

#[test]
fn tick_heals_members_that_are_actually_online() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.m().disconnected(BOB);
    h.set_online(&[BOB]);
    h.clock.advance(Duration::from_secs(360));
    h.m().tick();
    assert!(h.party(BOB).member(BOB).unwrap().online());
}

// --- chat ---------------------------------------------------------------------------------------------

#[test]
fn chat_reaches_the_whole_party() {
    let h = Harness::new();
    assert_eq!(Outcome::NotInParty, h.m().chat(ALICE, "hi", 0, Vec::new));
    h.join(ALICE, BOB);
    assert_eq!(Outcome::Success, h.m().chat(BOB, "<red>hi", 0, Vec::new));
    let (recipients, sender, owner, message, _, sent_at) = h.last_chat();
    assert_eq!(set(&[ALICE, BOB]), set(&recipients));
    assert_eq!("<red>hi", message, "raw text is kept; the renderer never parses it");
    assert_eq!(BOB, sender);
    assert_eq!(ALICE, owner);
    assert_eq!(h.clock.millis(), sent_at);
}

#[test]
fn mentions_keep_only_other_party_members() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.m().chat(BOB, "@Alice @Bob @Dave go", 0, || vec![ALICE, BOB, DAVE]);
    assert_eq!(vec![ALICE], h.last_chat().4, "not the sender, not outsiders");
}

#[test]
fn mentions_are_only_resolved_for_lines_that_go_out() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.m().toggle(ALICE, Toggle::Mute);
    let refuse = || -> Vec<Uuid> { panic!("refused lines must not look names up") };
    assert_eq!(Outcome::ChatMuted, h.m().chat(BOB, "@Alice", 0, refuse));
    assert_eq!(Outcome::NotInParty, h.m().chat(DAVE, "@Alice", 0, refuse));
}

#[test]
fn party_chat_can_be_turned_off_muted_or_slowed() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    h.m().promote(ALICE, CAROL);

    assert_eq!(Outcome::NoPermission, h.m().toggle(BOB, Toggle::Mute));
    assert_eq!(Outcome::Success, h.m().toggle(CAROL, Toggle::Mute), "moderators moderate chat");
    assert_eq!(Outcome::ChatMuted, h.m().chat(BOB, "hey", 0, Vec::new));
    assert_eq!(Outcome::Success, h.m().chat(CAROL, "staff can talk", 0, Vec::new));
    h.m().toggle(ALICE, Toggle::Mute);

    assert_eq!(Outcome::Success, h.m().set_slow_mode(CAROL, 10));
    h.assert_notice("settings.slow-mode", BOB);
    assert_eq!(Outcome::Success, h.m().chat(BOB, "one", 0, Vec::new));
    h.clock.advance(Duration::from_millis(9_200));
    assert_eq!(Outcome::SlowMode, h.m().chat(BOB, "two", 0, Vec::new));
    let (recipients, values) = h.notice("error.slow-mode");
    assert_eq!(vec![("time", "1s".to_owned())], values, "rounded up");
    assert_eq!(vec![BOB], recipients);
    assert_eq!(Outcome::Success, h.m().chat(CAROL, "staff skip it", 0, Vec::new));
    h.clock.advance(Duration::from_millis(800));
    assert_eq!(Outcome::Success, h.m().chat(BOB, "two", 0, Vec::new));
    let too_long = i64::from(PartySettings::MAX_SLOW_MODE_SECONDS) + 1;
    assert_eq!(Outcome::InvalidValue, h.m().set_slow_mode(ALICE, too_long));
    h.assert_notice("error.slow-mode-range", ALICE);
    assert_eq!(Outcome::Success, h.m().set_slow_mode(ALICE, 0));
    h.assert_notice("settings.slow-mode-off", BOB);

    assert_eq!(Outcome::NoPermission, h.m().toggle(CAROL, Toggle::Chat), "settings is off for moderators");
    assert_eq!(Outcome::Success, h.m().toggle(ALICE, Toggle::Chat));
    assert_eq!(Outcome::ChatDisabled, h.m().chat(ALICE, "anyone?", 0, Vec::new), "off means off, owner included");
}

#[test]
fn muted_player_is_blocked_and_party_gets_rate_limited_notice() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    let muted_until = h.clock.millis() + 3_600_000;
    assert_eq!(Outcome::Muted, h.m().chat(BOB, "hello", muted_until, Vec::new));
    assert_eq!(Outcome::Muted, h.m().chat(BOB, "hello?", muted_until, Vec::new));
    assert_eq!(1, h.count("chat.muted-notice"), "second attempt inside the cooldown is silent");
    assert_eq!(vec![ALICE], h.notice("chat.muted-notice").0);
    assert!(!h.events().iter().any(|e| matches!(e, PartyEvent::Chat { .. })));

    h.clock.advance(Duration::from_secs(30));
    h.m().chat(BOB, "again", i64::MAX, Vec::new);
    assert_eq!(2, h.count("chat.muted-notice"));
}

// --- API ----------------------------------------------------------------------------------------------

#[test]
fn create_makes_a_party_of_one_that_lasts_until_it_changes() {
    let h = Harness::new();
    assert_eq!(Outcome::PlayerOffline, h.m().create(ALICE));
    h.set_online(&[ALICE]);
    assert_eq!(Outcome::Success, h.m().create(ALICE));

    let party = h.party(ALICE);
    assert_eq!(vec![ALICE], party.member_ids());
    h.assert_notice("party.created", ALICE);
    assert!(h.events().iter().any(|e| matches!(e, PartyEvent::PartyChanged { party: Some(p), affected, previous: None } if p.id == party.id && *affected == vec![ALICE])));
    assert_eq!(Outcome::AlreadyInParty, h.m().create(ALICE));
    h.m().tick();
    assert!(h.m().party_of(ALICE).is_some(), "nothing is due for a party of one");

    assert_eq!(Outcome::Success, h.m().invite(ALICE, BOB));
    assert_eq!(party.id, h.party(ALICE).id, "invites reuse the created party");
    assert_eq!(Outcome::Success, h.m().deny(BOB, ALICE));
    h.assert_notice("disband.empty", ALICE);
    assert!(h.m().party_of(ALICE).is_none());
}

#[test]
fn add_puts_online_players_straight_in() {
    let h = Harness::new();
    h.set_online(&[BOB, CAROL, DAVE]);
    assert_eq!(Outcome::NotInParty, h.m().add(ALICE, BOB));
    h.m().invite(ALICE, BOB);
    h.clear();

    assert_eq!(Outcome::Success, h.m().add(ALICE, BOB));
    let party = h.party(BOB);
    assert_eq!(vec![ALICE, BOB], party.member_ids());
    assert!(party.invites.is_empty(), "the pending invite is consumed");
    h.assert_notice("member.joined", ALICE);
    h.assert_notice("party.joined", BOB);

    assert_eq!(Outcome::CannotTargetSelf, h.m().add(ALICE, ALICE));
    assert_eq!(Outcome::NotLeader, h.m().add(BOB, CAROL));
    assert_eq!(Outcome::TargetInParty, h.m().add(ALICE, BOB));
    assert_eq!(Outcome::PlayerOffline, h.m().add(ALICE, Uuid::new_v4()));
    assert_eq!(Outcome::Success, h.m().add(ALICE, CAROL));
    assert_eq!(Outcome::Success, h.m().add(ALICE, DAVE));
    let erin = Uuid::new_v4();
    h.set_online(&[erin]);
    assert_eq!(Outcome::PartyFull, h.m().add(ALICE, erin));
}

#[test]
fn other_plugins_can_cancel_create_join_disband_and_chat() {
    let h = Harness::new();
    h.set_online(&[ALICE, BOB]);
    h.guard.cancelled.lock().unwrap().insert(Action::Create);
    assert_eq!(Outcome::Cancelled, h.m().invite(ALICE, BOB));
    assert_eq!(Outcome::Cancelled, h.m().create(ALICE));
    assert!(h.m().party_of(ALICE).is_none());
    assert!(h.events().is_empty(), "a cancelled action has no side effect: {:?}", h.events());

    h.guard.cancelled.lock().unwrap().clear();
    h.m().invite(ALICE, BOB);
    let create = h.guard.last();
    assert_eq!((Action::Create, ALICE, vec![ALICE]), (create.action, create.player, create.members));
    assert_eq!(h.party(ALICE).id, create.party_id, "plugins see the real party id");

    h.guard.cancelled.lock().unwrap().insert(Action::Join);
    assert_eq!(Outcome::Cancelled, h.m().accept(BOB, ALICE));
    assert_eq!(Outcome::Cancelled, h.m().add(ALICE, BOB));
    assert!(h.m().party_of(BOB).is_none());
    assert!(h.party(ALICE).invite_for(BOB, h.clock.millis()).is_some(), "invite kept");
    let join = h.guard.last();
    assert_eq!((Action::Join, BOB, vec![ALICE]), (join.action, join.player, join.members));

    h.guard.cancelled.lock().unwrap().clear();
    h.m().accept(BOB, ALICE);
    h.guard.cancelled.lock().unwrap().extend([Action::Disband, Action::Chat]);
    h.clear();
    assert_eq!(Outcome::Cancelled, h.m().disband(ALICE));
    assert_eq!(Outcome::Cancelled, h.m().chat(BOB, "gg", 0, Vec::new));
    let chat = h.guard.last();
    assert_eq!(
        (Action::Chat, BOB, vec![ALICE, BOB], "gg".to_owned()),
        (chat.action, chat.player, chat.members, chat.message)
    );
    assert_eq!(vec![ALICE, BOB], h.party(ALICE).member_ids());
    assert!(h.events().is_empty(), "no chat line, no disband: {:?}", h.events());
}

#[test]
fn chat_reaches_the_party_as_it_is_after_the_check() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    // While plugins decide, another call changes the party: it must find nothing locked.
    let manager = h.manager.clone();
    *h.guard.during.lock().unwrap() = Some(Box::new(move || {
        manager.kick(ALICE, CAROL);
    }));
    assert_eq!(Outcome::Success, h.m().chat(BOB, "hi", 0, Vec::new));
    assert_eq!(set(&[ALICE, BOB]), set(&h.last_chat().0));
}

#[test]
fn failed_preconditions_and_unwatched_players_skip_the_guard() {
    let h = Harness::new();
    assert_eq!(Outcome::NoInvite, h.m().accept(BOB, ALICE));
    assert_eq!(Outcome::NotInParty, h.m().disband(ALICE));
    assert!(h.guard.checks.lock().unwrap().is_empty(), "no event for actions that can't happen");

    h.guard.not_watching.store(true, Ordering::SeqCst);
    h.guard.cancelled.lock().unwrap().extend([Action::Create, Action::Join, Action::Disband, Action::Chat]);
    h.join(ALICE, BOB);
    assert_eq!(Outcome::Success, h.m().chat(BOB, "hi", 0, Vec::new));
    assert_eq!(Outcome::Success, h.m().disband(ALICE));
    assert!(h.guard.checks.lock().unwrap().is_empty());
}

#[test]
fn everyone_who_leaves_a_party_is_reported_with_it() {
    let h = Harness::new();
    h.join(ALICE, BOB);
    h.join(ALICE, CAROL);
    h.m().kick(ALICE, CAROL);
    h.assert_left(&[CAROL], &[ALICE, BOB, CAROL]);
    h.m().leave(BOB);
    h.assert_left(&[BOB], &[ALICE, BOB]);
    h.assert_left(&[ALICE], &[ALICE]);
    assert!(
        !h.events().iter().any(|e| matches!(e, PartyEvent::PartyChanged { party: Some(_), previous: Some(_), .. }))
    );
}
