//! The wiring the Pumpkin plugin relies on: player events and commands in, effects out (Java's `PartiesCoreTest`).

mod common;

use std::time::Duration;

use common::{ALICE, BOB, CAROL, TestClock};
use parties_core::config::{BUNDLED_CONFIG, PluginConfig};
use parties_core::{Effect, Messages, NoGuard, PartiesCore, Sender, split_args};
use uuid::Uuid;

struct World {
    clock: TestClock,
    core: PartiesCore,
}

fn sender(id: Uuid, name: &str) -> Sender {
    Sender { id, name: name.to_owned(), server: Some("world".to_owned()), can_spy: false }
}

impl World {
    fn new() -> World {
        World::with_config(BUNDLED_CONFIG)
    }

    fn with_config(config: &str) -> World {
        let clock = TestClock::new();
        let config = PluginConfig::parse(config).unwrap();
        let core = PartiesCore::new(config, Messages::defaults(), clock.clock(), Box::new(NoGuard), 7);
        for (id, name) in [(ALICE, "Alice"), (BOB, "Bob"), (CAROL, "Carol")] {
            core.player_joined(id, name, 8, Some("world"));
        }
        core.drain_effects();
        World { clock, core }
    }

    fn party(&self, id: Uuid, name: &str, line: &str) -> Vec<Effect> {
        self.core.party_command(&sender(id, name), &split_args(line));
        self.core.drain_effects()
    }

    fn pc(&self, sender: &Sender, line: &str) -> Vec<Effect> {
        self.core.chat_command(sender, &split_args(line));
        self.core.drain_effects()
    }
}

/// Plain text of every chat line `to` got.
fn seen(effects: &[Effect], to: Uuid) -> Vec<String> {
    effects
        .iter()
        .filter_map(|e| match e {
            Effect::Message { to: t, text } | Effect::SpyMessage { to: t, text } if *t == to => Some(text.plain_text()),
            _ => None,
        })
        .collect()
}

#[track_caller]
fn assert_saw(effects: &[Effect], to: Uuid, needle: &str) {
    let lines = seen(effects, to);
    assert!(lines.iter().any(|l| l.contains(needle)), "{to} never saw {needle:?}: {lines:#?}");
}

#[test]
fn invites_and_accepts_through_the_commands() {
    let w = World::new();
    let effects = w.party(ALICE, "Alice", "Bob");
    assert_saw(&effects, BOB, "Alice invited you to their party!");
    assert_saw(&effects, ALICE, "Alice invited Bob to the party. They have 1m to accept.");
    let effects = w.party(BOB, "Bob", "accept alice");
    assert_saw(&effects, BOB, "You joined Alice's party!");
    assert_saw(&effects, ALICE, "Bob joined the party.");
    let list = w.party(BOB, "Bob", "list");
    let text = &seen(&list, BOB)[0];
    assert!(text.contains("Party Members (2/8)"), "{text}");
    assert!(text.contains("Owner » ● Alice") && text.contains("Member » ● Bob"), "{text}");
}

#[test]
fn errors_usage_and_help() {
    let w = World::new();
    assert_saw(&w.party(ALICE, "Alice", "Nobody"), ALICE, "No online player named Nobody.");
    assert_saw(&w.party(ALICE, "Alice", "kick"), ALICE, "Usage: /party kick <player>");
    assert_saw(&w.party(ALICE, "Alice", "leave"), ALICE, "You are not in a party.");
    assert_saw(&w.party(ALICE, "Alice", "accept Bob"), ALICE, "You have no pending invite from that player.");
    assert_saw(&w.party(ALICE, "Alice", ""), ALICE, "Party Commands");
    assert_saw(&w.party(ALICE, "Alice", "two words"), ALICE, "Party Commands");
    assert_saw(&w.party(ALICE, "Alice", "invite Alice"), ALICE, "You can't target yourself.");
    w.party(ALICE, "Alice", "invite Bob");
    w.party(BOB, "Bob", "accept Alice");
    assert_saw(&w.party(ALICE, "Alice", "kick Carol"), ALICE, "That player is not in your party.");
    assert_saw(&w.party(BOB, "Bob", "settings maxsize lots"), BOB, "Usage: /party settings");
}

#[test]
fn locked_chat_is_diverted_into_party_chat() {
    let w = World::new();
    assert!(!w.core.chat(ALICE, "public"));
    assert_saw(&w.pc(&sender(ALICE, "Alice"), "toggle"), ALICE, "You are not in a party.");
    w.party(ALICE, "Alice", "invite Bob");
    w.party(BOB, "Bob", "accept Alice");
    assert_saw(&w.pc(&sender(BOB, "Bob"), ""), BOB, "Party chat locked on.");
    assert!(w.core.chat(BOB, "hello team"));
    let effects = w.core.drain_effects();
    assert_saw(&effects, ALICE, "[Party] Bob » hello team");
    assert!(seen(&effects, CAROL).is_empty(), "outsiders see nothing");

    w.party(BOB, "Bob", "leave");
    assert!(!w.core.chat(BOB, "back to public"), "leaving the party drops the lock");
}

#[test]
fn mentions_show_an_action_bar_and_play_the_configured_sound() {
    let w = World::new();
    w.party(ALICE, "Alice", "invite Bob");
    w.party(BOB, "Bob", "accept Alice");
    let effects = w.pc(&sender(BOB, "Bob"), "hey @alice and @Carol, mail me@x.org");
    assert!(effects.iter().any(|e| matches!(e, Effect::ActionBar { to, text } if *to == ALICE && text.plain_text() == "Bob mentioned you in party chat")));
    assert!(effects.contains(&Effect::Sound {
        to: ALICE,
        sound: "ENTITY_EXPERIENCE_ORB_PICKUP".to_owned(),
        volume: 1.0,
        pitch: 1.0
    }));
    assert!(
        !effects.iter().any(|e| matches!(e, Effect::ActionBar { to, .. } | Effect::Sound { to, .. } if *to != ALICE))
    );
    let line = effects
        .iter()
        .find_map(|e| match e {
            Effect::Message { to, text } if *to == ALICE => Some(text.clone()),
            _ => None,
        })
        .unwrap();
    assert_eq!("[Party] Bob » hey @Alice and @Carol, mail me@x.org", line.plain_text());
    assert_eq!(Some(true), line.find("@Alice").unwrap().style.bold, "the member's tag is highlighted");
    assert_eq!(None, line.find("@Carol").unwrap().style.bold, "outsiders aren't");
}

#[test]
fn staff_can_spy_on_every_party_chat() {
    let w = World::new();
    w.party(ALICE, "Alice", "invite Bob");
    w.party(BOB, "Bob", "accept Alice");
    let plain = sender(CAROL, "Carol");
    // Without the permission it's an ordinary message, and Carol has no party.
    assert_saw(&w.pc(&plain, "spy"), CAROL, "You are not in a party.");
    let staff = Sender { can_spy: true, ..plain };
    assert_saw(&w.pc(&staff, "spy"), CAROL, "Party chat spy on.");
    let effects = w.pc(&sender(BOB, "Bob"), "secret plan");
    assert!(effects.iter().any(|e| matches!(e, Effect::SpyMessage { to, text } if *to == CAROL && text.plain_text() == "[Spy] Alice's party » Bob » secret plan")));
    w.core.stop_spying(CAROL);
    assert!(seen(&w.pc(&sender(BOB, "Bob"), "again"), CAROL).is_empty());
}

#[test]
fn settings_menu_and_toggles() {
    let w = World::new();
    assert_saw(&w.party(ALICE, "Alice", "settings"), ALICE, "You are not in a party.");
    w.party(ALICE, "Alice", "invite Bob");
    w.party(BOB, "Bob", "accept Alice");
    let menu = &seen(&w.party(ALICE, "Alice", "settings"), ALICE)[0];
    assert!(menu.contains("Auto-warp » ON") && menu.contains("Public party » OFF"), "{menu}");
    assert!(menu.contains("Max size » 8") && menu.contains("Slow mode » OFF"), "{menu}");
    assert_saw(&w.party(ALICE, "Alice", "settings PUBLIC"), BOB, "Alice turned public on.");
    assert_saw(&w.party(ALICE, "Alice", "settings maxsize 3"), BOB, "Alice set the party size to 3.");
    assert_saw(&w.party(ALICE, "Alice", "settings maxsize 99"), ALICE, "between 2 and 8");
    assert_saw(&w.party(ALICE, "Alice", "settings slowmode 1m30s"), BOB, "slow mode to 1m30s");
    assert_saw(&w.party(ALICE, "Alice", "settings slowmode 11m"), ALICE, "at most 600 seconds");
    assert_saw(&w.party(ALICE, "Alice", "settings slowmode off"), BOB, "slow mode off");
    assert_saw(&w.party(BOB, "Bob", "settings chat"), BOB, "Your party role doesn't allow that.");
    let menu = &seen(&w.party(ALICE, "Alice", "settings"), ALICE)[0];
    assert!(menu.contains("Public party » ON") && menu.contains("Max size » 3"), "{menu}");
    assert_saw(&w.party(CAROL, "Carol", "join Alice"), CAROL, "You joined Alice's party!");
}

#[test]
fn warp_and_auto_warp_pull_members_to_the_leader() {
    let w = World::with_config("auto-warp:\n  servers: [\"world_*\"]\n");
    w.party(ALICE, "Alice", "invite Bob");
    w.party(BOB, "Bob", "accept Alice");
    let effects = w.party(ALICE, "Alice", "warp");
    assert!(effects.contains(&Effect::Warp {
        members: vec![BOB],
        leader: ALICE,
        server: "world".to_owned(),
        delay: Duration::ZERO,
        skip_if_there: false,
    }));
    assert_saw(&effects, ALICE, "Warping 1 member(s) to world.");
    assert_saw(&effects, BOB, "Alice summoned you to world.");

    w.core.server_switched(ALICE, "world_nether");
    let effects = w.core.drain_effects();
    assert!(effects.contains(&Effect::Warp {
        members: vec![BOB],
        leader: ALICE,
        server: "world_nether".to_owned(),
        delay: Duration::from_secs(1),
        skip_if_there: true,
    }));
    assert_saw(&effects, BOB, "Following Alice to world_nether...");
    w.core.server_switched(ALICE, "world");
    assert!(!w.core.drain_effects().iter().any(|e| matches!(e, Effect::Warp { .. })), "not a game world");
}

#[test]
fn disconnected_members_keep_their_slot() {
    let w = World::new();
    w.party(ALICE, "Alice", "invite Bob");
    w.party(BOB, "Bob", "accept Alice");
    w.core.player_left(BOB);
    let effects = w.core.drain_effects();
    assert_saw(&effects, ALICE, "Bob disconnected. They have 5m to rejoin before being removed.");
    assert_saw(&w.party(ALICE, "Alice", "list"), ALICE, "Member » ● Bob");

    w.core.player_joined(BOB, "Bob", 8, None);
    assert_saw(&w.core.drain_effects(), ALICE, "Bob reconnected.");
    w.core.player_left(BOB);
    w.clock.advance(Duration::from_secs(300));
    w.core.tick();
    let effects = w.core.drain_effects();
    assert_saw(&effects, ALICE, "Bob was removed from the party after being offline too long.");
    assert_saw(&effects, ALICE, "The party was disbanded: nobody else is in it.");
    assert!(effects.iter().any(|e| matches!(e, Effect::Left { player, .. } if *player == ALICE)));
}

#[test]
fn size_permissions_of_the_owner_raise_the_limit() {
    let w = World::new();
    w.core.network().set_size_limit(ALICE, 12);
    w.party(ALICE, "Alice", "invite Bob");
    w.party(BOB, "Bob", "accept Alice");
    assert_saw(&w.party(BOB, "Bob", "list"), BOB, "(2/12)");
    assert_eq!(vec![ALICE], w.core.online_leaders());
}

#[test]
fn muted_players_are_told_how_long() {
    let w = World::new();
    w.party(ALICE, "Alice", "invite Bob");
    w.party(BOB, "Bob", "accept Alice");
    w.core.network().set_muted_until(BOB, w.clock.millis() + 90_000);
    let effects = w.pc(&sender(BOB, "Bob"), "let me talk");
    assert_saw(&effects, BOB, "Your mute ends in 1m30s.");
    assert_saw(&effects, ALICE, "I am currently muted");
    w.core.network().set_muted_until(BOB, i64::MAX);
    assert_saw(&w.pc(&sender(BOB, "Bob"), "please"), BOB, "You are permanently muted");
}

#[test]
fn api_actions_deliver_their_messages() {
    let w = World::new();
    assert_eq!(parties_core::Outcome::Success, w.core.act(|m| m.create(ALICE)));
    assert_saw(&w.core.drain_effects(), ALICE, "You created a party.");
    assert_eq!(parties_core::Outcome::Success, w.core.act(|m| m.add(ALICE, BOB)));
    let info = w.core.party_info(&w.core.manager().party_of(BOB).unwrap());
    assert_eq!((ALICE, vec![ALICE, BOB], Some("world".to_owned())), (info.leader, info.members, info.server));
}
