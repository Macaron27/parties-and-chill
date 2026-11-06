//! Shared fixtures: a settable clock, recorded events and a recording guard (Java's `PartyManagerContract`).
#![allow(dead_code)]

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use parties_core::event::PartyEvent;
use parties_core::manager::{Action, Clock, Hooks, Outcome, PartyGuard, PartyManager};
use parties_core::party::{Party, PartySettings};
use parties_core::rules::{PartyRules, Right, SizePermission};
use uuid::Uuid;

pub const ALICE: Uuid = Uuid::from_u128(0xa);
pub const BOB: Uuid = Uuid::from_u128(0xb);
pub const CAROL: Uuid = Uuid::from_u128(0xc);
pub const DAVE: Uuid = Uuid::from_u128(0xd);

/// 2026-01-01T00:00:00Z.
pub const START: i64 = 1_767_225_600_000;

#[derive(Clone)]
pub struct TestClock(pub Arc<AtomicI64>);

impl TestClock {
    pub fn new() -> TestClock {
        TestClock(Arc::new(AtomicI64::new(START)))
    }

    pub fn advance(&self, duration: Duration) {
        self.0.fetch_add(duration.as_millis() as i64, Ordering::SeqCst);
    }

    pub fn millis(&self) -> i64 {
        self.0.load(Ordering::SeqCst)
    }

    pub fn clock(&self) -> Clock {
        let now = self.0.clone();
        Arc::new(move || now.load(Ordering::SeqCst))
    }
}

/// One `allows` call: action, player, member ids of the party shown, message, party id.
#[derive(Clone, Debug, PartialEq)]
pub struct Check {
    pub action: Action,
    pub player: Uuid,
    pub members: Vec<Uuid>,
    pub message: String,
    pub party_id: Uuid,
}

#[derive(Default)]
pub struct GuardState {
    pub checks: Mutex<Vec<Check>>,
    pub cancelled: Mutex<HashSet<Action>>,
    pub not_watching: AtomicBool,
    /// Runs once, inside the next check (other code acting while plugins decide).
    pub during: Mutex<Option<Box<dyn FnOnce() + Send>>>,
}

impl GuardState {
    pub fn last(&self) -> Check {
        self.checks.lock().unwrap().last().cloned().expect("no check")
    }
}

pub struct RecordingGuard(pub Arc<GuardState>);

impl PartyGuard for RecordingGuard {
    fn watches(&self, _: Action, _: Uuid) -> bool {
        !self.0.not_watching.load(Ordering::SeqCst)
    }

    fn allows(&self, action: Action, player: Uuid, party: &Party, message: &str) -> bool {
        self.0.checks.lock().unwrap().push(Check {
            action,
            player,
            members: party.member_ids(),
            message: message.to_owned(),
            party_id: party.id,
        });
        let during = self.0.during.lock().unwrap().take();
        if let Some(during) = during {
            during();
        }
        !self.0.cancelled.lock().unwrap().contains(&action)
    }
}

pub fn rules(moderator_rights: &[Right]) -> PartyRules {
    rules_with(moderator_rights, PartySettings::DEFAULTS)
}

pub fn rules_with(moderator_rights: &[Right], defaults: PartySettings) -> PartyRules {
    PartyRules::new(
        4,
        Duration::from_secs(60),
        Duration::from_secs(300),
        Duration::from_secs(1),
        vec!["bw-*".to_owned()],
        Duration::from_secs(30),
        vec![SizePermission::new("parties.size.6", 6).unwrap()],
        moderator_rights.iter().copied().collect(),
        defaults,
    )
    .unwrap()
}

pub const DEFAULT_RIGHTS: [Right; 5] =
    [Right::Invite, Right::Kick, Right::Warp, Right::StartGames, Right::ModerateChat];

/// A manager with recorded events, a presence set, per-player size limits and a recording guard.
pub struct Harness {
    pub clock: TestClock,
    pub events: Arc<Mutex<Vec<PartyEvent>>>,
    pub online: Arc<Mutex<HashSet<Uuid>>>,
    pub size_limits: Arc<Mutex<HashMap<Uuid, u32>>>,
    pub guard: Arc<GuardState>,
    pub manager: Arc<PartyManager>,
}

impl Harness {
    pub fn new() -> Harness {
        Harness::with_rules(rules(&DEFAULT_RIGHTS))
    }

    pub fn with_rules(rules: PartyRules) -> Harness {
        let clock = TestClock::new();
        let events = Arc::new(Mutex::new(Vec::new()));
        let online = Arc::new(Mutex::new(HashSet::new()));
        let size_limits = Arc::new(Mutex::new(HashMap::new()));
        let guard = Arc::new(GuardState::default());
        let manager = Arc::new(Harness::manager(&clock, &events, &online, &size_limits, &guard, rules));
        Harness { clock, events, online, size_limits, guard, manager }
    }

    fn manager(
        clock: &TestClock,
        events: &Arc<Mutex<Vec<PartyEvent>>>,
        online: &Arc<Mutex<HashSet<Uuid>>>,
        size_limits: &Arc<Mutex<HashMap<Uuid, u32>>>,
        guard: &Arc<GuardState>,
        rules: PartyRules,
    ) -> PartyManager {
        let (events, online, size_limits) = (events.clone(), online.clone(), size_limits.clone());
        let hooks = Hooks {
            events: Box::new(move |e| events.lock().unwrap().push(e)),
            is_online: Box::new(move |id| online.lock().unwrap().contains(&id)),
            size_limit: Box::new(move |id| size_limits.lock().unwrap().get(&id).copied().unwrap_or(0)),
            guard: Box::new(RecordingGuard(guard.clone())),
        };
        PartyManager::new(rules, clock.clock(), hooks, 42)
    }

    /// Replaces the manager (new rules), keeping the fixtures.
    pub fn renew(&mut self, rules: PartyRules) {
        self.manager =
            Arc::new(Harness::manager(&self.clock, &self.events, &self.online, &self.size_limits, &self.guard, rules));
    }

    pub fn m(&self) -> &PartyManager {
        &self.manager
    }

    pub fn join(&self, leader: Uuid, member: Uuid) {
        assert_eq!(Outcome::Success, self.m().invite(leader, member));
        assert_eq!(Outcome::Success, self.m().accept(member, leader));
    }

    pub fn set_online(&self, ids: &[Uuid]) {
        self.online.lock().unwrap().extend(ids);
    }

    pub fn events(&self) -> Vec<PartyEvent> {
        self.events.lock().unwrap().clone()
    }

    pub fn clear(&self) {
        self.events.lock().unwrap().clear();
    }

    pub fn party(&self, player: Uuid) -> Arc<Party> {
        self.m().party_of(player).unwrap_or_else(|| panic!("{player} has no party"))
    }

    pub fn has_notice(&self, key: &str, recipient: Uuid) -> bool {
        self.events().iter().any(|e| matches!(e, PartyEvent::Notice { key: k, recipients, .. } if *k == key && recipients.contains(&recipient)))
    }

    #[track_caller]
    pub fn assert_notice(&self, key: &str, recipient: Uuid) {
        assert!(self.has_notice(key, recipient), "expected notice {key} for {recipient} in {:#?}", self.events());
    }

    /// The last notice with `key`: (recipients, values).
    pub fn notice(&self, key: &str) -> (Vec<Uuid>, Vec<(&'static str, String)>) {
        self.events()
            .into_iter()
            .rev()
            .find_map(|e| match e {
                PartyEvent::Notice { key: k, recipients, values, .. } if k == key => Some((recipients, values)),
                _ => None,
            })
            .unwrap_or_else(|| panic!("no notice {key}"))
    }

    pub fn count(&self, key: &str) -> usize {
        self.events().iter().filter(|e| matches!(e, PartyEvent::Notice { key: k, .. } if *k == key)).count()
    }

    /// The last chat line: (recipients, sender, owner, message, mentioned, sent_at).
    pub fn last_chat(&self) -> (Vec<Uuid>, Uuid, Uuid, String, Vec<Uuid>, i64) {
        self.events()
            .into_iter()
            .rev()
            .find_map(|e| match e {
                PartyEvent::Chat { recipients, sender, owner, message, mentioned, sent_at } => {
                    Some((recipients, sender, owner, message, mentioned, sent_at))
                }
                _ => None,
            })
            .expect("no chat line")
    }

    /// Warps: (members as a set, server, delay ms).
    pub fn warps(&self) -> Vec<(HashSet<Uuid>, String, u128)> {
        self.events()
            .into_iter()
            .filter_map(|e| match e {
                PartyEvent::Warp { members, server, delay, .. } => {
                    Some((members.into_iter().collect(), server, delay.as_millis()))
                }
                _ => None,
            })
            .collect()
    }

    /// Asserts a "they left" change for `players`, carrying the party as it was (`members`).
    #[track_caller]
    pub fn assert_left(&self, players: &[Uuid], members: &[Uuid]) {
        let players: HashSet<Uuid> = players.iter().copied().collect();
        let found = self.events().iter().any(|e| match e {
            PartyEvent::PartyChanged { affected, party: None, previous: Some(previous) } => {
                affected.iter().copied().collect::<HashSet<_>>() == players && previous.member_ids() == members
            }
            _ => false,
        });
        assert!(found, "expected {players:?} to leave {members:?} in {:#?}", self.events());
    }

    pub fn role(&self, player: Uuid) -> parties_core::party::PartyRole {
        self.party(player).role(player).unwrap()
    }
}

pub fn set(ids: &[Uuid]) -> HashSet<Uuid> {
    ids.iter().copied().collect()
}
