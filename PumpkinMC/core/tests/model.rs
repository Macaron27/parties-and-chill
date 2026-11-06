//! Party model, rules, durations, the store's sweep index and the network (Java's `PartyTest`, `PartyRulesTest`,
//! `DurationsTest`, `LocalNetworkTest` and the store part of `PartyManagerContract`).

mod common;

use std::cell::RefCell;
use std::collections::HashSet;
use std::time::Duration;

use common::*;
use parties_core::durations;
use parties_core::network::LocalNetwork;
use parties_core::party::{Invite, Party, PartyMember, PartyRole, PartySettings, Toggle};
use parties_core::rules::{PartyRules, Right, SizePermission};
use parties_core::store::InMemoryPartyStore;
use uuid::Uuid;

fn rules(max_size: u32, servers: &[&str], sizes: Vec<SizePermission>, moderator: &[Right]) -> PartyRules {
    PartyRules::new(
        max_size,
        Duration::from_secs(60),
        Duration::from_secs(300),
        Duration::from_secs(1),
        servers.iter().map(|s| (*s).to_owned()).collect(),
        Duration::from_secs(30),
        sizes,
        moderator.iter().copied().collect(),
        PartySettings::DEFAULTS,
    )
    .unwrap()
}

#[test]
fn game_server_globs() {
    let rules = rules(8, &["bw-*", "duels-?", "skywars.solo"], vec![], &[]);
    assert!(rules.is_game_server("bw-12"));
    assert!(rules.is_game_server("BW-lobby-less"));
    assert!(rules.is_game_server("duels-1"));
    assert!(!rules.is_game_server("duels-10"));
    assert!(rules.is_game_server("skywars.solo"));
    assert!(!rules.is_game_server("skywarsXsolo"), "dots are literal, not regex wildcards");
    assert!(!rules.is_game_server("lobby-1"));
    let worlds = rules_any(&["world_*"]);
    assert!(worlds.is_game_server("world_nether"));
    assert!(!worlds.is_game_server("world"));
}

fn rules_any(servers: &[&str]) -> PartyRules {
    rules(8, servers, vec![], &[])
}

#[test]
fn rejects_tiny_and_huge_parties() {
    let built = PartyRules::new(
        1,
        Duration::ZERO,
        Duration::ZERO,
        Duration::ZERO,
        vec![],
        Duration::ZERO,
        vec![],
        HashSet::new(),
        PartySettings::DEFAULTS,
    );
    assert_eq!("max-size: party size must be 2..1024, got 1", built.unwrap_err());
    assert!(SizePermission::new("parties.size.2000", 2000).is_err());
    assert!(SizePermission::new("parties.size.1", 1).is_err());
}

#[test]
fn the_biggest_granted_size_wins() {
    let sizes = vec![
        SizePermission::new("vip", 12).unwrap(),
        SizePermission::new("mvp++", 24).unwrap(),
        SizePermission::new("mvp", 16).unwrap(),
    ];
    let rules = rules(8, &[], sizes, &[]);
    assert_eq!(8, rules.size_limit(|_| false), "no permission: the default");
    assert_eq!(12, rules.size_limit(|p| p == "vip"));
    assert_eq!(24, rules.size_limit(|p| ["vip", "mvp++", "mvp"].contains(&p)), "several: the highest");
    assert_eq!(16, rules.size_limit(|p| ["mvp", "vip"].contains(&p)));
}

#[test]
fn lookup_stops_at_the_first_granted_size_and_skips_sizes_below_the_default() {
    let sizes = vec![
        SizePermission::new("small", 4).unwrap(),
        SizePermission::new("mid", 12).unwrap(),
        SizePermission::new("big", 24).unwrap(),
    ];
    let rules = rules(10, &[], sizes, &[]);
    let asked = RefCell::new(Vec::new());
    let has = |p: &str| {
        asked.borrow_mut().push(p.to_owned());
        p == "mid" || p == "small"
    };
    assert_eq!(12, rules.size_limit(has));
    assert_eq!(vec!["big", "mid"], *asked.borrow(), "biggest first, stops on a grant");
    asked.borrow_mut().clear();
    assert_eq!(
        10,
        rules.size_limit(|p| {
            asked.borrow_mut().push(p.to_owned());
            false
        })
    );
    assert_eq!(vec!["big", "mid"], *asked.borrow(), "sizes below max-size can't win: never checked");
}

#[test]
fn owners_have_every_right_moderators_the_configured_ones_members_none() {
    let rules = rules(8, &[], vec![], &[Right::Kick]);
    let all = [
        Right::Invite,
        Right::Kick,
        Right::Warp,
        Right::Promote,
        Right::Settings,
        Right::StartGames,
        Right::ModerateChat,
    ];
    for right in all {
        assert!(rules.grants(PartyRole::Owner, right));
        assert_eq!(right == Right::Kick, rules.grants(PartyRole::Moderator, right));
        assert!(!rules.grants(PartyRole::Member, right));
    }
}

#[test]
fn party_keeps_roles_in_line_with_the_leader() {
    let party = Party::create(ALICE, 0, PartySettings::DEFAULTS)
        .with_member(PartyMember::joined(BOB, 1))
        .with_member(PartyMember::joined(CAROL, 2));
    assert_eq!(Some(PartyRole::Owner), party.role(ALICE));
    let handed = party.with_role(BOB, PartyRole::Moderator).with_leader(CAROL);
    assert_eq!(Some(PartyRole::Owner), handed.role(CAROL));
    assert_eq!(Some(PartyRole::Moderator), handed.role(ALICE), "the previous owner stays as moderator");
    assert_eq!(Some(PartyRole::Moderator), handed.role(BOB));
    assert_eq!(vec![ALICE, BOB, CAROL], handed.member_ids(), "join order kept");
    let rest = handed.without_member(CAROL, BOB);
    assert_eq!(Some(PartyRole::Owner), rest.role(BOB));
    assert!(PartyRole::Owner.outranks(PartyRole::Moderator) && !PartyRole::Member.outranks(PartyRole::Member));
}

#[test]
fn deadlines_and_invites() {
    let party = Party::create(ALICE, 0, PartySettings::DEFAULTS);
    assert_eq!(i64::MAX, party.next_deadline());
    let invited = party.with_invite(Invite { target: BOB, inviter: ALICE, expires_at: 100 });
    assert!(invited.invite_for(BOB, 99).is_some());
    assert!(invited.invite_for(BOB, 100).is_none(), "expired at the instant");
    let again = invited.with_invite(Invite { target: BOB, inviter: ALICE, expires_at: 50 });
    assert_eq!(1, again.invites.len(), "one invite per target");
    let away = again.with_member(PartyMember::joined(CAROL, 0)).update_member(CAROL, |m| m.disconnected(30));
    assert_eq!(30, away.next_deadline());
    assert_eq!(vec![ALICE], away.online_member_ids());
}

#[test]
fn settings_toggles() {
    let settings = PartySettings::DEFAULTS;
    for toggle in Toggle::ALL {
        assert_eq!(Some(toggle), Toggle::by_key(&toggle.key().to_uppercase()));
        assert_eq!(!settings.get(toggle), settings.with(toggle, !settings.get(toggle)).get(toggle));
    }
    assert_eq!(None, Toggle::by_key("maxsize"));
    assert_eq!(12, settings.capped_at(12));
    assert_eq!(5, settings.with_max_size(5).capped_at(12));
    assert_eq!(12, settings.with_max_size(20).capped_at(12));
}

#[test]
fn sweep_index_only_reports_parties_with_something_due() {
    let mut store = InMemoryPartyStore::default();
    let idle = Party::create(ALICE, 0, PartySettings::DEFAULTS).with_member(PartyMember::joined(BOB, 0));
    store.save(idle.clone());
    assert!(!store.any_due(i64::MAX - 1));
    assert!(store.due(i64::MAX - 1).is_empty());

    let invited = idle.with_invite(Invite { target: CAROL, inviter: ALICE, expires_at: 1000 });
    store.save(invited.clone());
    assert!(!store.any_due(999));
    assert!(store.any_due(1000));
    assert_eq!(1, store.due(1000).len());

    let accepted = invited.without_invite(CAROL).with_member(PartyMember::joined(CAROL, 5));
    store.save(accepted.clone());
    assert!(!store.any_due(i64::MAX - 1), "accepting clears the deadline");
    assert_eq!(accepted.id, store.by_member(CAROL).unwrap().id);

    let left = accepted.without_member(BOB, ALICE);
    store.save(left.clone());
    assert!(store.by_member(BOB).is_none(), "member index updated");
    store.delete(&left);
    assert!(store.by_member(ALICE).is_none() && store.all().is_empty());
}

#[test]
fn parses_config_style_durations() {
    assert_eq!(Duration::from_secs(60), durations::parse("60s").unwrap());
    assert_eq!(Duration::from_secs(60), durations::parse("60").unwrap());
    assert_eq!(Duration::from_secs(300), durations::parse("5m").unwrap());
    assert_eq!(Duration::from_secs(90), durations::parse("1m 30s").unwrap());
    assert_eq!(Duration::from_millis(1500), durations::parse("1s500ms").unwrap());
    assert_eq!(Duration::from_secs(7200), durations::parse(" 2H ").unwrap());
    assert_eq!(Duration::from_secs(86_400 + 60), durations::parse("1d1m").unwrap());
}

#[test]
fn rejects_garbage_durations() {
    for bad in ["", "abc", "5 minutes", "10x", "-5s", "5m trailing", "99999999999999999999d"] {
        assert!(durations::parse(bad).is_err(), "{bad}");
    }
    assert_eq!("invalid duration: 'soon'", durations::parse("soon").unwrap_err());
}

#[test]
fn formats_durations_compactly() {
    assert_eq!("5m", durations::format(Duration::from_secs(300)));
    assert_eq!("1m30s", durations::format(Duration::from_secs(90)));
    assert_eq!("1d2h", durations::format(Duration::from_secs(26 * 3600)));
    assert_eq!("0s", durations::format(Duration::from_millis(400)));
}

#[test]
fn mention_sounds_resolve_to_vanilla_keys() {
    use parties_core::sound::key;
    assert_eq!(Some("minecraft:entity.experience_orb.pickup".to_owned()), key("ENTITY_EXPERIENCE_ORB_PICKUP"));
    assert_eq!(Some("minecraft:entity.experience_orb.pickup".to_owned()), key("orb_pickup"), "1.8 names too");
    assert_eq!(Some("minecraft:block.note_block.pling".to_owned()), key("Minecraft:Block.Note_Block.Pling"));
    assert_eq!(Some("minecraft:ui.button.click".to_owned()), key("ui.button.click"), "namespace added");
    assert_eq!(Some("custompack:ding".to_owned()), key("custompack:ding"));
    assert_eq!(None, key("ENTITY_GHAST_SCREAM"), "unknown Bukkit names are reported, not guessed");
}

#[test]
fn network_keeps_names_of_departed_players_for_a_while() {
    let clock = TestClock::new();
    let network = LocalNetwork::new(clock.clock(), Duration::from_secs(1200));
    network.player_joined(ALICE, "Alice", 12);
    network.server_switched(ALICE, "world");
    assert_eq!(Some(ALICE), network.find_online("alice"));
    assert_eq!(Some("world".to_owned()), network.server_of(ALICE));

    network.player_left(ALICE);
    assert!(!network.is_online(ALICE));
    assert_eq!(None, network.find_online("Alice"), "not online any more");
    assert_eq!(Some(ALICE), network.uuid_of("ALICE"), "still resolvable");
    assert_eq!(Some("Alice".to_owned()), network.name_of(ALICE));
    assert_eq!(12, network.size_limit(ALICE));
    assert_eq!(None, network.server_of(ALICE));
    network.server_switched(ALICE, "late"); // a switch handled after the logout
    assert_eq!(None, network.server_of(ALICE));

    clock.advance(Duration::from_secs(1200));
    network.prune();
    assert_eq!(None, network.uuid_of("alice"));
    assert_eq!(None, network.name_of(ALICE));
    assert_eq!(0, network.size_limit(ALICE));
}

#[test]
fn renamed_players_and_mutes() {
    let clock = TestClock::new();
    let network = LocalNetwork::new(clock.clock(), Duration::from_secs(60));
    network.player_joined(BOB, "Bob", 8);
    network.player_joined(BOB, "Robert", 8);
    assert_eq!(None, network.find_online("bob"));
    assert_eq!(Some(BOB), network.find_online("robert"));
    network.set_size_limit(BOB, 16);
    assert_eq!(16, network.size_limit(BOB));
    network.set_size_limit(Uuid::new_v4(), 16); // offline: ignored

    network.set_muted_until(BOB, clock.millis() + 1000);
    assert_eq!(clock.millis() + 1000, network.muted_until(BOB));
    clock.advance(Duration::from_secs(1));
    assert_eq!(0, network.muted_until(BOB), "mutes expire");
    network.set_muted_until(BOB, i64::MAX);
    network.set_muted_until(BOB, 0);
    assert_eq!(0, network.muted_until(BOB), "0 lifts it");
}
