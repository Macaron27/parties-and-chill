//! `PartyManager`: all party rules (Hypixel parity). It reads and writes [`InMemoryPartyStore`] snapshots and
//! reports every side effect as a [`PartyEvent`]; it never talks to players directly.
//!
//! Events are published only after the store lock is released, and the [`PartyGuard`] (other plugins' veto) is
//! asked before taking it, so a guard that calls back into the plugin never finds the store locked.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use uuid::Uuid;

use crate::durations;
use crate::event::{PartyEvent, distinct};
use crate::party::{Invite, Millis, Party, PartyMember, PartyRole, PartySettings, Toggle};
use crate::rules::{PartyRules, Right};
use crate::store::InMemoryPartyStore;

/// Result of a player action. Anything but [`Outcome::Success`] names the error message to show the actor.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    Success,
    NotInParty,
    /// Only the owner may do that (disband, demote, hand the party over).
    NotLeader,
    /// The actor's role lacks the right (see `roles.moderator` and the party settings).
    NoPermission,
    /// The target's role is not below the actor's.
    TargetOutranks,
    NotModerator,
    CannotTargetSelf,
    AlreadyInParty,
    TargetInParty,
    TargetNotInParty,
    AlreadyInvited,
    NoInvite,
    NotPublic,
    PartyFull,
    NoOneToWarp,
    Muted,
    ChatDisabled,
    ChatMuted,
    PlayerOffline,
    /// Slow mode is on and the sender is too fast; they were told how long to wait.
    SlowMode,
    /// A setting value out of range; the actor was told the valid range.
    InvalidValue,
    /// Another plugin cancelled the action (it tells the player why).
    Cancelled,
}

impl Outcome {
    /// The `messages.yml` key describing this outcome to the actor; empty if they were already told.
    pub fn message_key(self) -> &'static str {
        match self {
            Outcome::Success | Outcome::SlowMode | Outcome::InvalidValue | Outcome::Cancelled => "",
            Outcome::NotInParty => "error.not-in-party",
            Outcome::NotLeader => "error.not-leader",
            Outcome::NoPermission => "error.no-permission",
            Outcome::TargetOutranks => "error.target-outranks",
            Outcome::NotModerator => "error.not-moderator",
            Outcome::CannotTargetSelf => "error.self",
            Outcome::AlreadyInParty => "error.already-in-party",
            Outcome::TargetInParty => "error.target-in-party",
            Outcome::TargetNotInParty => "error.target-not-in-party",
            Outcome::AlreadyInvited => "error.already-invited",
            Outcome::NoInvite => "error.no-invite",
            Outcome::NotPublic => "error.not-public",
            Outcome::PartyFull => "error.party-full",
            Outcome::NoOneToWarp => "error.no-one-to-warp",
            Outcome::Muted => "error.muted",
            Outcome::ChatDisabled => "error.chat-disabled",
            Outcome::ChatMuted => "error.chat-muted",
            Outcome::PlayerOffline => "error.player-offline",
        }
    }
}

/// What other plugins can cancel.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Action {
    Create,
    Join,
    Disband,
    Chat,
}

/// Lets other plugins cancel party actions (the API's cancellable events). Called outside the store lock.
pub trait PartyGuard: Send + Sync {
    /// Cheap: the manager only prepares a check (extra store reads) when someone may cancel.
    fn watches(&self, action: Action, player: Uuid) -> bool;

    /// `player` acts: the leader creating or disbanding, the player joining, the chat sender. `party` is the party
    /// right before the action (for [`Action::Create`], the party about to be created); `message` is the chat line
    /// for [`Action::Chat`], `""` otherwise. `false` if a plugin cancelled the action.
    fn allows(&self, action: Action, player: Uuid, party: &Party, message: &str) -> bool;
}

/// Nobody can cancel anything.
pub struct NoGuard;

impl PartyGuard for NoGuard {
    fn watches(&self, _: Action, _: Uuid) -> bool {
        false
    }

    fn allows(&self, _: Action, _: Uuid, _: &Party, _: &str) -> bool {
        true
    }
}

/// The time source; injectable so expiry is testable without sleeping.
pub type Clock = Arc<dyn Fn() -> Millis + Send + Sync>;

pub(crate) fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Callbacks into the rest of the plugin.
pub struct Hooks {
    /// Receives every side effect, after the store lock is released.
    pub events: Box<dyn Fn(PartyEvent) + Send + Sync>,
    /// Presence check, used to ignore stale disconnects.
    pub is_online: Box<dyn Fn(Uuid) -> bool + Send + Sync>,
    /// The party size a player's permissions grant ([`PartyRules::size_limit`]); asked for owners, `0` if unknown.
    pub size_limit: Box<dyn Fn(Uuid) -> u32 + Send + Sync>,
    /// Lets other plugins cancel create / join / disband / chat.
    pub guard: Box<dyn PartyGuard>,
}

pub struct PartyManager {
    store: Mutex<InMemoryPartyStore>,
    rules: PartyRules,
    clock: Clock,
    hooks: Hooks,
    /// Picks the new leader when a disconnected leader times out (xorshift64*).
    random: Mutex<u64>,
    last_muted_notice: Mutex<HashMap<Uuid, Millis>>,
    last_chat: Mutex<HashMap<Uuid, Millis>>,
}

type Players = Vec<(&'static str, Uuid)>;
type Values = Vec<(&'static str, String)>;

fn notice(to: impl IntoIterator<Item = Uuid>, key: &'static str, players: Players, values: Values) -> PartyEvent {
    PartyEvent::Notice { recipients: distinct(to), key, players, values }
}

fn others(party: &Party, player: Uuid) -> Vec<Uuid> {
    party.member_ids().into_iter().filter(|id| *id != player).collect()
}

fn others_online(party: &Party, player: Uuid) -> Vec<Uuid> {
    party.online_member_ids().into_iter().filter(|id| *id != player).collect()
}

fn joinable(party: &Party, player: Uuid, now: Millis) -> bool {
    party.settings.open || party.invite_for(player, now).is_some()
}

fn check_leader(party: Option<&Party>, leader: Uuid) -> Result<&Party, Outcome> {
    match party {
        None => Err(Outcome::NotInParty),
        Some(p) if !p.is_leader(leader) => Err(Outcome::NotLeader),
        Some(p) => Ok(p),
    }
}

impl PartyManager {
    /// `seed` drives the successor pick (any value; `0` is replaced).
    pub fn new(rules: PartyRules, clock: Clock, hooks: Hooks, seed: u64) -> PartyManager {
        PartyManager {
            store: Mutex::new(InMemoryPartyStore::default()),
            rules,
            clock,
            hooks,
            random: Mutex::new(seed.max(1)),
            last_muted_notice: Mutex::new(HashMap::new()),
            last_chat: Mutex::new(HashMap::new()),
        }
    }

    pub fn rules(&self) -> &PartyRules {
        &self.rules
    }

    fn now(&self) -> Millis {
        (self.clock)()
    }

    /// The party `player` belongs to.
    pub fn party_of(&self, player: Uuid) -> Option<Arc<Party>> {
        lock(&self.store).by_member(player)
    }

    /// Every party.
    pub fn parties(&self) -> Vec<Arc<Party>> {
        lock(&self.store).all()
    }

    /// The size limit the owner's permissions grant to `party` (never below `party.max-size`).
    pub fn size_limit(&self, party: &Party) -> u32 {
        self.rules.max_size.max((self.hooks.size_limit)(party.leader))
    }

    /// How many members `party` may have: its owner's permission limit, lowered by its maxsize setting.
    pub fn max_size(&self, party: &Party) -> u32 {
        party.settings.capped_at(self.size_limit(party))
    }

    fn full(&self, party: &Party) -> bool {
        party.size() >= self.max_size(party) as usize
    }

    /// `true` if `player` may use `right` in `party`: by role (owner, or moderator as configured), or because the
    /// party lets every member invite / warp.
    pub fn can(&self, party: &Party, player: Uuid, right: Right) -> bool {
        let Some(role) = party.role(player) else { return false };
        self.rules.grants(role, right)
            || right == Right::Invite && party.settings.all_invite
            || right == Right::Warp && party.settings.all_warp
    }

    /// Invites `target`, creating a party led by `inviter` if they have none. Expires after the invite timeout.
    pub fn invite(&self, inviter: Uuid, target: Uuid) -> Outcome {
        if inviter == target {
            return Outcome::CannotTargetSelf;
        }
        // The first invite creates the party. The vetted party is the one saved, so plugins see its real id.
        let mut created = None;
        if self.hooks.guard.watches(Action::Create, inviter) && self.party_of(inviter).is_none() {
            let party = Party::create(inviter, self.now(), self.rules.party_defaults);
            if !self.hooks.guard.allows(Action::Create, inviter, &party, "") {
                return Outcome::Cancelled;
            }
            created = Some(party);
        }
        self.write(|store, out| {
            let now = self.now();
            if store.by_member(target).is_some() {
                return Outcome::TargetInParty;
            }
            let original = store.by_member(inviter);
            if let Some(o) = &original
                && !self.can(o, inviter, Right::Invite)
            {
                return Outcome::NoPermission;
            }
            let party = match &original {
                Some(o) => (**o).clone(),
                None => created.unwrap_or_else(|| Party::create(inviter, now, self.rules.party_defaults)),
            };
            if party.invite_for(target, now).is_some() {
                return Outcome::AlreadyInvited;
            }
            if self.full(&party) {
                return Outcome::PartyFull;
            }
            let ttl = self.rules.invite_ttl;
            let party = party.with_invite(Invite { target, inviter, expires_at: now + durations::millis(ttl) });
            let time = vec![("time", durations::format(ttl))];
            out.push(notice([target], "invite.received", vec![("player", inviter)], time.clone()));
            out.push(notice(party.member_ids(), "invite.sent", vec![("player", inviter), ("target", target)], time));
            self.commit(store, original.as_deref(), Some(party), out);
            Outcome::Success
        })
    }

    /// Accepts the invite sent to `target` by the party of `inviter`.
    pub fn accept(&self, target: Uuid, inviter: Uuid) -> Outcome {
        if self.hooks.guard.watches(Action::Join, target) {
            let now = self.now();
            let Some(party) = self.party_of(inviter).filter(|p| p.invite_for(target, now).is_some()) else {
                return Outcome::NoInvite;
            };
            if !self.hooks.guard.allows(Action::Join, target, &party, "") {
                return Outcome::Cancelled;
            }
        }
        self.write(|store, out| {
            let now = self.now();
            let Some(original) = store.by_member(inviter).filter(|p| p.invite_for(target, now).is_some()) else {
                return Outcome::NoInvite;
            };
            if store.by_member(target).is_some() {
                return Outcome::AlreadyInParty;
            }
            if self.full(&original) {
                return Outcome::PartyFull;
            }
            self.join_party(store, &original, target, now, out);
            Outcome::Success
        })
    }

    /// Joins the party of `member` without being invited, if it is public (an invite from it works too).
    pub fn join(&self, player: Uuid, member: Uuid) -> Outcome {
        if player == member {
            return Outcome::CannotTargetSelf;
        }
        if self.hooks.guard.watches(Action::Join, player) {
            let now = self.now();
            let Some(party) = self.party_of(member).filter(|p| joinable(p, player, now)) else {
                return Outcome::NotPublic;
            };
            if !self.hooks.guard.allows(Action::Join, player, &party, "") {
                return Outcome::Cancelled;
            }
        }
        self.write(|store, out| {
            let now = self.now();
            let Some(original) = store.by_member(member).filter(|p| joinable(p, player, now)) else {
                return Outcome::NotPublic;
            };
            if store.by_member(player).is_some() {
                return Outcome::AlreadyInParty;
            }
            if self.full(&original) {
                return Outcome::PartyFull;
            }
            self.join_party(store, &original, player, now, out);
            Outcome::Success
        })
    }

    /// (API) Creates a party led by `leader` alone. Like any party of one, it is disbanded the next time its members
    /// change while no invite is pending (e.g. when the leader disconnects).
    pub fn create(&self, leader: Uuid) -> Outcome {
        if !(self.hooks.is_online)(leader) {
            return Outcome::PlayerOffline; // an offline "online" leader would never time out
        }
        let party = Party::create(leader, self.now(), self.rules.party_defaults);
        if self.hooks.guard.watches(Action::Create, leader) {
            if self.party_of(leader).is_some() {
                return Outcome::AlreadyInParty;
            }
            if !self.hooks.guard.allows(Action::Create, leader, &party, "") {
                return Outcome::Cancelled;
            }
        }
        self.write(|store, out| {
            if store.by_member(leader).is_some() {
                return Outcome::AlreadyInParty;
            }
            let ids = party.member_ids();
            // Not commit(): its lonely-party rule would delete a party of one right away.
            let saved = store.save(party);
            out.push(notice([leader], "party.created", vec![], vec![]));
            out.push(PartyEvent::PartyChanged { affected: ids, party: Some(saved), previous: None });
            Outcome::Success
        })
    }

    /// (API, leader only) Puts `target` in the party right away, consuming any invite they had.
    pub fn add(&self, leader: Uuid, target: Uuid) -> Outcome {
        if leader == target {
            return Outcome::CannotTargetSelf;
        }
        if !(self.hooks.is_online)(target) {
            return Outcome::PlayerOffline;
        }
        if self.hooks.guard.watches(Action::Join, target) {
            let party = self.party_of(leader);
            let party = match check_leader(party.as_deref(), leader) {
                Ok(p) => p.clone(),
                Err(denied) => return denied,
            };
            if !self.hooks.guard.allows(Action::Join, target, &party, "") {
                return Outcome::Cancelled;
            }
        }
        self.write(|store, out| {
            let original = store.by_member(leader);
            let original = match check_leader(original.as_deref(), leader) {
                Ok(p) => p.clone(),
                Err(denied) => return denied,
            };
            if store.by_member(target).is_some() {
                return Outcome::TargetInParty;
            }
            if self.full(&original) {
                return Outcome::PartyFull;
            }
            self.join_party(store, &original, target, self.now(), out);
            Outcome::Success
        })
    }

    fn join_party(
        &self,
        store: &mut InMemoryPartyStore,
        original: &Party,
        target: Uuid,
        now: Millis,
        out: &mut Vec<PartyEvent>,
    ) {
        let party = original.without_invite(target).with_member(PartyMember::joined(target, now));
        out.push(notice(original.member_ids(), "member.joined", vec![("player", target)], vec![]));
        out.push(notice([target], "party.joined", vec![("player", party.leader)], vec![]));
        self.commit(store, Some(original), Some(party), out);
    }

    /// Declines the invite sent to `target` by the party of `inviter`. A party left with only its leader and no
    /// pending invite is disbanded.
    pub fn deny(&self, target: Uuid, inviter: Uuid) -> Outcome {
        self.write(|store, out| {
            let now = self.now();
            let Some(original) = store.by_member(inviter).filter(|p| p.invite_for(target, now).is_some()) else {
                return Outcome::NoInvite;
            };
            let party = original.without_invite(target);
            out.push(notice([target], "invite.denied-self", vec![("player", inviter)], vec![]));
            out.push(notice(party.member_ids(), "invite.denied", vec![("player", target)], vec![]));
            self.commit(store, Some(&original), Some(party), out);
            Outcome::Success
        })
    }

    /// Leaves the current party. A leaving owner hands over to the oldest online moderator, else member.
    pub fn leave(&self, player: Uuid) -> Outcome {
        self.write(|store, out| {
            let Some(original) = store.by_member(player) else { return Outcome::NotInParty };
            out.push(notice([player], "party.left", vec![], vec![]));
            let party = self.drop_member(&original, player, out, "member.left", vec![("player", player)], false);
            self.commit(store, Some(&original), party, out);
            Outcome::Success
        })
    }

    /// Removes `target`: owners kick anyone, moderators (if allowed) kick plain members.
    pub fn kick(&self, actor: Uuid, target: Uuid) -> Outcome {
        self.write(|store, out| {
            let original = store.by_member(actor);
            if let Err(denied) = self.check_targeting(original.as_deref(), actor, target, Right::Kick) {
                return denied;
            }
            let Some(original) = original else { return Outcome::NotInParty };
            out.push(notice([target], "party.kicked", vec![("player", actor)], vec![]));
            let placeholders = vec![("player", target), ("leader", actor)];
            let party = self.drop_member(&original, target, out, "member.kicked", placeholders, false);
            self.commit(store, Some(&original), party, out);
            Outcome::Success
        })
    }

    /// Hypixel's promote: a member becomes moderator (owner, or moderators allowed to promote); a moderator becomes
    /// owner (owner only), and the previous owner stays on as moderator.
    pub fn promote(&self, actor: Uuid, target: Uuid) -> Outcome {
        self.write(|store, out| {
            let Some(original) = store.by_member(actor) else { return Outcome::NotInParty };
            if actor == target {
                return Outcome::CannotTargetSelf;
            }
            let party = match original.role(target) {
                None => return Outcome::TargetNotInParty,
                Some(PartyRole::Member) => {
                    if !self.can(&original, actor, Right::Promote) {
                        return Outcome::NoPermission;
                    }
                    let party = original.with_role(target, PartyRole::Moderator);
                    let players = vec![("player", actor), ("target", target)];
                    out.push(notice(party.member_ids(), "role.promoted", players, vec![]));
                    party
                }
                Some(PartyRole::Moderator) => {
                    if !original.is_leader(actor) {
                        return Outcome::NotLeader;
                    }
                    let party = original.with_leader(target);
                    let players = vec![("player", actor), ("target", target)];
                    out.push(notice(party.member_ids(), "leader.promoted", players, vec![]));
                    party
                }
                Some(PartyRole::Owner) => return Outcome::TargetOutranks,
            };
            self.commit(store, Some(&original), Some(party), out);
            Outcome::Success
        })
    }

    /// (Owner only) Turns moderator `target` back into a plain member.
    pub fn demote(&self, owner: Uuid, target: Uuid) -> Outcome {
        self.write(|store, out| {
            let original = store.by_member(owner);
            let original = match check_leader(original.as_deref(), owner) {
                Ok(p) => p.clone(),
                Err(denied) => return denied,
            };
            if owner == target {
                return Outcome::CannotTargetSelf;
            }
            match original.role(target) {
                None => return Outcome::TargetNotInParty,
                Some(PartyRole::Moderator) => {}
                Some(_) => return Outcome::NotModerator,
            }
            let party = original.with_role(target, PartyRole::Member);
            out.push(notice(party.member_ids(), "role.demoted", vec![("player", owner), ("target", target)], vec![]));
            self.commit(store, Some(&original), Some(party), out);
            Outcome::Success
        })
    }

    /// (Owner only) Destroys the party.
    pub fn disband(&self, leader: Uuid) -> Outcome {
        if self.hooks.guard.watches(Action::Disband, leader) {
            let party = self.party_of(leader);
            let party = match check_leader(party.as_deref(), leader) {
                Ok(p) => p.clone(),
                Err(denied) => return denied,
            };
            if !self.hooks.guard.allows(Action::Disband, leader, &party, "") {
                return Outcome::Cancelled;
            }
        }
        self.write(|store, out| {
            let party = store.by_member(leader);
            let party = match check_leader(party.as_deref(), leader) {
                Ok(_) => party.unwrap_or_else(|| unreachable!("checked above")),
                Err(denied) => return denied,
            };
            store.delete(&party);
            out.push(notice(party.member_ids(), "disband.leader", vec![("player", leader)], vec![]));
            out.push(PartyEvent::PartyChanged { affected: party.member_ids(), party: None, previous: Some(party) });
            Outcome::Success
        })
    }

    /// Pulls every other online member to the actor right away (owner, allowed moderators, or anyone when the
    /// party allows members to warp). `server` is the actor's current server (on Pumpkin: world).
    pub fn warp(&self, actor: Uuid, server: &str) -> Outcome {
        let Some(party) = self.party_of(actor) else { return Outcome::NotInParty };
        if !self.can(&party, actor, Right::Warp) {
            return Outcome::NoPermission;
        }
        let members = others_online(&party, actor);
        if members.is_empty() {
            return Outcome::NoOneToWarp;
        }
        let count = members.len().to_string();
        self.publish(vec![
            PartyEvent::Warp {
                members: members.clone(),
                server: server.to_owned(),
                leader: actor,
                delay: Duration::ZERO,
                skip_if_there: false,
            },
            notice(members, "warp.summoned", vec![("player", actor)], vec![("server", server.to_owned())]),
            notice([actor], "warp.sent", vec![], vec![("server", server.to_owned()), ("count", count)]),
        ]);
        Outcome::Success
    }

    /// Auto-warp hook, called after a player changed servers (on Pumpkin: worlds). If they may start games (owner,
    /// or moderator as configured), the party has auto-warp on and the server is a game server, the online members
    /// ranked below them follow after the warp delay. Pulling only downwards keeps two starters entering different
    /// games from warping each other back and forth, and moderators from dragging the owner out of a game.
    pub fn switched_server(&self, player: Uuid, party: &Party, server: &str) {
        if !party.settings.auto_warp || !self.rules.is_game_server(server) {
            return;
        }
        let Some(role) = party.role(player) else { return };
        if !self.rules.grants(role, Right::StartGames) {
            return;
        }
        let members: Vec<Uuid> = others_online(party, player)
            .into_iter()
            .filter(|id| role.outranks(party.role(*id).unwrap_or(PartyRole::Owner)))
            .collect();
        if members.is_empty() {
            return;
        }
        self.publish(vec![
            PartyEvent::Warp {
                members: members.clone(),
                server: server.to_owned(),
                leader: player,
                delay: self.rules.warp_delay,
                skip_if_there: true,
            },
            notice(members, "warp.following", vec![("player", player)], vec![("server", server.to_owned())]),
        ]);
    }

    /// Flips `toggle`: [`Toggle::Mute`] needs [`Right::ModerateChat`], the rest [`Right::Settings`].
    pub fn toggle(&self, actor: Uuid, toggle: Toggle) -> Outcome {
        let right = if toggle == Toggle::Mute { Right::ModerateChat } else { Right::Settings };
        self.configure(actor, right, |party, out| {
            let value = !party.settings.get(toggle);
            let key = if value { "settings.enabled" } else { "settings.disabled" };
            out.push(notice(
                party.member_ids(),
                key,
                vec![("player", actor)],
                vec![("setting", toggle.key().to_owned())],
            ));
            Some(party.settings.with(toggle, value))
        })
    }

    /// Caps the party at `size` members, up to what the owner's permissions allow; `0` resets the cap to the
    /// owner's permission limit.
    pub fn set_max_size(&self, actor: Uuid, size: i64) -> Outcome {
        self.configure(actor, Right::Settings, |party, out| {
            let limit = self.size_limit(party);
            if size != 0 && (size < 2 || size > i64::from(limit)) {
                out.push(notice([actor], "error.max-size-range", vec![], vec![("max", limit.to_string())]));
                return None;
            }
            let size = u32::try_from(size).unwrap_or(0);
            let shown = if size == 0 { limit } else { size };
            out.push(notice(
                party.member_ids(),
                "settings.max-size",
                vec![("player", actor)],
                vec![("max", shown.to_string())],
            ));
            Some(party.settings.with_max_size(size))
        })
    }

    /// Sets party chat slow mode for plain members; `0` turns it off.
    pub fn set_slow_mode(&self, actor: Uuid, seconds: i64) -> Outcome {
        self.configure(actor, Right::ModerateChat, |party, out| {
            let max = PartySettings::MAX_SLOW_MODE_SECONDS;
            let Some(seconds) = u32::try_from(seconds).ok().filter(|s| *s <= max) else {
                out.push(notice([actor], "error.slow-mode-range", vec![], vec![("max", max.to_string())]));
                return None;
            };
            out.push(if seconds == 0 {
                notice(party.member_ids(), "settings.slow-mode-off", vec![("player", actor)], vec![])
            } else {
                let time = durations::format(Duration::from_secs(u64::from(seconds)));
                notice(party.member_ids(), "settings.slow-mode", vec![("player", actor)], vec![("time", time)])
            });
            Some(party.settings.with_slow_mode(seconds))
        })
    }

    /// Computes new settings (or `None` for an invalid value, after telling the actor) from the locked party.
    fn configure(
        &self,
        actor: Uuid,
        right: Right,
        change: impl FnOnce(&Party, &mut Vec<PartyEvent>) -> Option<PartySettings>,
    ) -> Outcome {
        self.write(|store, out| {
            let Some(original) = store.by_member(actor) else { return Outcome::NotInParty };
            if !self.can(&original, actor, right) {
                return Outcome::NoPermission;
            }
            let Some(settings) = change(&original, out) else { return Outcome::InvalidValue };
            store.save(original.with_settings(settings)); // members unchanged: no lonely-party rule
            Outcome::Success
        })
    }

    /// Sends a party chat line, honouring mutes, the party's chat switches and slow mode.
    ///
    /// `muted_until`: until when the sender is muted (`0`: not muted, `Millis::MAX`: permanently). Muted senders
    /// are blocked and their party receives a "currently muted" notice, at most once per muted-notice cooldown.
    /// `mentions`: players tagged with `@name`, resolved only once the line is allowed; only other members are kept.
    pub fn chat(
        &self,
        sender: Uuid,
        message: &str,
        muted_until: Millis,
        mentions: impl FnOnce() -> Vec<Uuid>,
    ) -> Outcome {
        let Some(mut party) = self.party_of(sender) else { return Outcome::NotInParty };
        let now = self.now();
        if muted_until > now {
            let cooldown = durations::millis(self.rules.muted_notice_cooldown);
            let mut notices = lock(&self.last_muted_notice);
            if notices.get(&sender).is_none_or(|last| now - last >= cooldown) {
                notices.insert(sender, now);
                drop(notices);
                self.publish(vec![notice(
                    others(&party, sender),
                    "chat.muted-notice",
                    vec![("player", sender)],
                    vec![],
                )]);
            }
            return Outcome::Muted;
        }
        if let Some(refused) = self.check_chat(&party, sender, now) {
            return refused;
        }
        if self.hooks.guard.watches(Action::Chat, sender) {
            if !self.hooks.guard.allows(Action::Chat, sender, &party, message) {
                return Outcome::Cancelled;
            }
            // The check may have let other code run: members may have changed.
            match self.party_of(sender) {
                Some(current) => party = current,
                None => return Outcome::NotInParty,
            }
        }
        lock(&self.last_chat).insert(sender, now);
        let tagged = distinct(mentions().into_iter().filter(|id| *id != sender && party.is_member(*id)));
        self.publish(vec![PartyEvent::Chat {
            recipients: party.member_ids(),
            sender,
            owner: party.leader,
            message: message.to_owned(),
            mentioned: tagged,
            sent_at: now,
        }]);
        Outcome::Success
    }

    /// Why `sender` may not talk right now, or `None` if they may.
    fn check_chat(&self, party: &Party, sender: Uuid, now: Millis) -> Option<Outcome> {
        let settings = party.settings;
        if !settings.chat {
            return Some(Outcome::ChatDisabled);
        }
        if party.role(sender).unwrap_or(PartyRole::Member) != PartyRole::Member {
            return None; // staff skip mute and slow mode
        }
        if settings.muted {
            return Some(Outcome::ChatMuted);
        }
        if settings.slow_mode_seconds == 0 {
            return None;
        }
        let last = lock(&self.last_chat).get(&sender).copied();
        let wait = last.map_or(0, |last| last + i64::from(settings.slow_mode_seconds) * 1000 - now);
        if wait <= 0 {
            return None;
        }
        // Rounded up: "wait 1s" rather than "wait 0s" with a few hundred ms left.
        let time = durations::format(Duration::from_secs(((wait + 999) / 1000) as u64));
        self.publish(vec![notice([sender], "error.slow-mode", vec![], vec![("time", time)])]);
        Some(Outcome::SlowMode)
    }

    /// Marks `player` as disconnected; they keep their slot for the disconnect grace. Ignored if they are back.
    pub fn disconnected(&self, player: Uuid) {
        self.write(|store, out| {
            if (self.hooks.is_online)(player) {
                return;
            }
            let Some(original) = store.by_member(player) else { return };
            if !original.member(player).is_some_and(PartyMember::online) {
                return;
            }
            let grace = self.rules.disconnect_grace;
            let drop_at = self.now() + durations::millis(grace);
            let party = original.update_member(player, |m| m.disconnected(drop_at));
            let key = if party.is_leader(player) { "leader.disconnected" } else { "member.disconnected" };
            let time = vec![("time", durations::format(grace))];
            out.push(notice(others(&party, player), key, vec![("player", player)], time));
            self.commit(store, Some(&original), Some(party), out);
        });
    }

    /// Restores a disconnected member who came back within the grace period.
    pub fn reconnected(&self, player: Uuid) {
        self.write(|store, out| {
            let Some(original) = store.by_member(player) else { return };
            if original.member(player).is_none_or(PartyMember::online) {
                return;
            }
            let party = original.update_member(player, PartyMember::reconnected);
            out.push(notice(others(&party, player), "member.reconnected", vec![("player", player)], vec![]));
            self.commit(store, Some(&original), Some(party), out);
        });
    }

    /// Expires invites and drops members whose grace period ran out. A timed-out leader is replaced by a random
    /// online member. Call every second.
    pub fn tick(&self) {
        let now = self.now();
        let cooldown = durations::millis(self.rules.muted_notice_cooldown);
        lock(&self.last_muted_notice).retain(|_, sent| now - *sent < cooldown);
        let slow_max = i64::from(PartySettings::MAX_SLOW_MODE_SECONDS) * 1000;
        lock(&self.last_chat).retain(|_, sent| now - *sent < slow_max);
        if !lock(&self.store).any_due(now) {
            return;
        }
        self.write(|store, out| {
            for party in store.due(now) {
                self.expire(store, &party, now, out);
            }
        });
    }

    fn expire(&self, store: &mut InMemoryPartyStore, original: &Party, now: Millis, out: &mut Vec<PartyEvent>) {
        let mut party = original.clone();
        for invite in original.invites.iter().filter(|i| i.expired(now)) {
            party = party.without_invite(invite.target);
            out.push(notice([invite.target], "invite.expired-target", vec![("player", invite.inviter)], vec![]));
            out.push(notice(party.member_ids(), "invite.expired", vec![("player", invite.target)], vec![]));
        }
        let mut current = Some(party);
        for member in &original.members {
            let Some(party) = current.take() else { break };
            if member.online() || member.drop_at > now {
                current = Some(party);
                continue;
            }
            if (self.hooks.is_online)(member.id) {
                // Missed reconnect: heal instead of dropping.
                current = Some(party.update_member(member.id, PartyMember::reconnected));
                continue;
            }
            current = self.drop_member(&party, member.id, out, "member.timed-out", vec![("player", member.id)], true);
        }
        self.commit(store, Some(original), current, out);
    }

    /// Removes `player`, cancels the invites they sent (accepting goes through the inviter's party, which they just
    /// left) and elects a successor if they led the party. `None` if nobody is left.
    fn drop_member(
        &self,
        party: &Party,
        player: Uuid,
        out: &mut Vec<PartyEvent>,
        key: &'static str,
        placeholders: Players,
        random_successor: bool,
    ) -> Option<Party> {
        out.push(PartyEvent::PartyChanged {
            affected: vec![player],
            party: None,
            previous: Some(Arc::new(party.clone())),
        });
        let invited: Vec<Uuid> = party.invites.iter().filter(|i| i.inviter == player).map(|i| i.target).collect();
        for target in &invited {
            out.push(notice([*target], "invite.expired-target", vec![("player", player)], vec![]));
        }
        if party.size() == 1 {
            return None;
        }
        let leader_left = party.is_leader(player);
        let next = if leader_left { self.successor(party, player, random_successor) } else { party.leader };
        let mut rest = party.without_member(player, next);
        for target in invited {
            rest = rest.without_invite(target);
        }
        out.push(notice(rest.member_ids(), key, placeholders, vec![]));
        if leader_left {
            out.push(notice(rest.member_ids(), "leader.changed", vec![("player", next)], vec![]));
        }
        Some(rest)
    }

    /// Online members before offline ones, moderators before plain members.
    fn successor(&self, party: &Party, leaving: Uuid, random: bool) -> Uuid {
        let online = others_online(party, leaving);
        let mut pool = if online.is_empty() { others(party, leaving) } else { online };
        let moderators: Vec<Uuid> =
            pool.iter().copied().filter(|id| party.role(*id) == Some(PartyRole::Moderator)).collect();
        if !moderators.is_empty() {
            pool = moderators;
        }
        if random { pool[self.next_random(pool.len())] } else { pool[0] }
    }

    fn next_random(&self, bound: usize) -> usize {
        let mut state = lock(&self.random);
        *state ^= *state >> 12;
        *state ^= *state << 25;
        *state ^= *state >> 27;
        let value = state.wrapping_mul(0x2545_F491_4F6C_DD1D);
        (value % bound as u64) as usize
    }

    /// Persists the new state: deletes the party when nobody is left to party with (one member and no pending
    /// invite), otherwise saves it and announces membership or leadership changes. `original` is `None` for a
    /// brand-new party; `party` is `None` if the last member left.
    fn commit(
        &self,
        store: &mut InMemoryPartyStore,
        original: Option<&Party>,
        party: Option<Party>,
        out: &mut Vec<PartyEvent>,
    ) {
        let Some(party) = party else {
            if let Some(original) = original {
                store.delete(original);
            }
            return;
        };
        if party.size() <= 1 && party.invites.is_empty() {
            store.delete(&party);
            out.push(notice(party.member_ids(), "disband.empty", vec![], vec![]));
            let affected = party.member_ids();
            out.push(PartyEvent::PartyChanged { affected, party: None, previous: Some(Arc::new(party)) });
            return;
        }
        let changed = original.is_none_or(|o| o.member_ids() != party.member_ids() || o.leader != party.leader);
        let affected = party.member_ids();
        let saved = store.save(party);
        if changed {
            out.push(PartyEvent::PartyChanged { affected, party: Some(saved), previous: None });
        }
    }

    /// Checks `actor` may use `right` on `target`, who must rank below them.
    fn check_targeting(&self, party: Option<&Party>, actor: Uuid, target: Uuid, right: Right) -> Result<(), Outcome> {
        let Some(party) = party else { return Err(Outcome::NotInParty) };
        if !self.can(party, actor, right) {
            return Err(Outcome::NoPermission);
        }
        if actor == target {
            return Err(Outcome::CannotTargetSelf);
        }
        let Some(target_role) = party.role(target) else { return Err(Outcome::TargetNotInParty) };
        if !party.role(actor).is_some_and(|role| role.outranks(target_role)) {
            return Err(Outcome::TargetOutranks);
        }
        Ok(())
    }

    fn write<T>(&self, operation: impl FnOnce(&mut InMemoryPartyStore, &mut Vec<PartyEvent>) -> T) -> T {
        let mut out = Vec::new();
        let result = {
            let mut store = lock(&self.store);
            operation(&mut store, &mut out)
        };
        self.publish(out);
        result
    }

    fn publish(&self, out: Vec<PartyEvent>) {
        for event in out {
            if let PartyEvent::Notice { recipients, .. } = &event
                && recipients.is_empty()
            {
                continue;
            }
            (self.hooks.events)(event);
        }
    }
}
