//! Parties & Chill's core, ported from `dev.partiesandchill.core` (the proxy plugin minus the proxy) and
//! independent of any server API: party rules, party chat, the `/party` and `/pchat` commands, `config.yml`,
//! `messages.yml` (MiniMessage) and the `pnc:main` bridge protocol.
//!
//! The platform glue feeds it player events and commands, then applies the [`Effect`]s it produced. No call into
//! the core ever calls back into the platform, so the glue can apply effects (which may re-enter the plugin, e.g.
//! a teleport firing events) without holding any of the core's locks.

pub mod bridge;
mod chat;
mod command;
pub mod config;
pub mod durations;
pub mod event;
pub mod manager;
pub mod messages;
pub mod minimessage;
pub mod network;
pub mod party;
pub mod rules;
pub mod sound;
pub mod store;
pub mod text;
pub mod timestamp;
mod yaml;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use uuid::Uuid;

pub use chat::{MAX_LENGTH, Mention, mentions};
pub use command::{PARTY_SUBCOMMANDS, split_args};
pub use config::{BUNDLED_CONFIG, PluginConfig};
pub use manager::{Action, Clock, NoGuard, Outcome, PartyGuard, PartyManager};
pub use messages::{BUNDLED_MESSAGES, Messages};
pub use party::{Millis, Party, PartyMember, PartyRole, PartySettings, Toggle};
pub use text::Text;

use event::PartyEvent;
use manager::{Hooks, lock};
use network::LocalNetwork;

/// Lets staff read every party's chat (`/pc spy`).
pub const SPY_PERMISSION: &str = "parties.admin.spy";

/// What the platform must do after a call into the core.
#[derive(Clone, Debug, PartialEq)]
pub enum Effect {
    /// A chat line for a player on this server.
    Message { to: Uuid, text: Text },
    /// A party chat line for a spying staff member: deliver it only if they still have [`SPY_PERMISSION`], else
    /// call [`PartiesCore::stop_spying`].
    SpyMessage { to: Uuid, text: Text },
    /// Text above the hotbar (party chat mentions).
    ActionBar { to: Uuid, text: Text },
    /// `chat.mention.sound`: a Bukkit `Sound` name or a namespaced key.
    Sound { to: Uuid, sound: String, volume: f32, pitch: f32 },
    /// Moves `members` to `leader` after `delay`; with `skip_if_there`, members already on `server` stay.
    Warp { members: Vec<Uuid>, leader: Uuid, server: String, delay: Duration, skip_if_there: bool },
    /// `player` left `party` (shown as it was before): the API's `PartyLeaveEvent`.
    Left { player: Uuid, party: Arc<Party> },
}

/// A player running a command, with what the platform knows about them.
#[derive(Clone, Debug)]
pub struct Sender {
    pub id: Uuid,
    pub name: String,
    /// The server (on Pumpkin: world) they are on, `None` while between servers.
    pub server: Option<String>,
    /// They have [`SPY_PERMISSION`].
    pub can_spy: bool,
}

#[derive(Default)]
struct ChatState {
    /// Players whose every chat line goes to party chat.
    locked: std::collections::HashSet<Uuid>,
    /// Staff reading every party's chat.
    spies: std::collections::HashSet<Uuid>,
}

/// The running plugin: config, party rules, chat and commands, for the players of one server.
pub struct PartiesCore {
    config: PluginConfig,
    messages: Messages,
    network: Arc<LocalNetwork>,
    manager: PartyManager,
    clock: Clock,
    events: Arc<Mutex<Vec<PartyEvent>>>,
    effects: Mutex<Vec<Effect>>,
    chat: Mutex<ChatState>,
}

impl PartiesCore {
    /// `guard` lets other plugins cancel party actions; `seed` drives the random successor pick.
    pub fn new(config: PluginConfig, messages: Messages, clock: Clock, guard: Box<dyn PartyGuard>, seed: u64) -> Self {
        // Names stay resolvable well beyond the disconnect grace, as in Java (grace × 4).
        let network = Arc::new(LocalNetwork::new(clock.clone(), config.party.disconnect_grace * 4));
        let events = Arc::new(Mutex::new(Vec::new()));
        let hooks = Hooks {
            events: {
                let events = events.clone();
                Box::new(move |event| lock(&events).push(event))
            },
            is_online: {
                let network = network.clone();
                Box::new(move |id| network.is_online(id))
            },
            // Refreshed by the platform at login and while online (see PartiesCore::online_leaders).
            size_limit: {
                let network = network.clone();
                Box::new(move |id| network.size_limit(id))
            },
            guard,
        };
        let manager = PartyManager::new(config.party.clone(), clock.clone(), hooks, seed);
        PartiesCore {
            config,
            messages,
            network,
            manager,
            clock,
            events,
            effects: Mutex::new(Vec::new()),
            chat: Mutex::new(ChatState::default()),
        }
    }

    pub fn config(&self) -> &PluginConfig {
        &self.config
    }

    pub fn messages(&self) -> &Messages {
        &self.messages
    }

    pub fn network(&self) -> &LocalNetwork {
        &self.network
    }

    pub fn manager(&self) -> &PartyManager {
        &self.manager
    }

    fn now(&self) -> Millis {
        (self.clock)()
    }

    /// A player finished joining, with the party size their permissions grant and the world they are in.
    pub fn player_joined(&self, id: Uuid, name: &str, size_limit: u32, server: Option<&str>) {
        self.network.player_joined(id, name, size_limit);
        if let Some(server) = server {
            self.network.server_switched(id, server);
        }
        self.manager.reconnected(id);
        self.dispatch();
    }

    /// A player's session ended: they keep their party slot for the disconnect grace.
    pub fn player_left(&self, id: Uuid) {
        self.forget(id);
        self.network.player_left(id);
        self.manager.disconnected(id);
        self.dispatch();
    }

    /// A player changed servers (on Pumpkin: worlds): records it and auto-warps their party if they entered a game
    /// server and may start games.
    pub fn server_switched(&self, id: Uuid, server: &str) {
        self.network.server_switched(id, server);
        if let Some(party) = self.manager.party_of(id) {
            self.manager.switched_server(id, &party, server);
        }
        self.dispatch();
    }

    /// The chat lock: `true` if the line went to party chat and the platform must cancel it.
    pub fn chat(&self, id: Uuid, message: &str) -> bool {
        if !self.is_locked(id) {
            return false;
        }
        self.send_chat(id, message, 0);
        true
    }

    /// The 1 s sweep: expires invites, drops members whose grace ran out, forgets stale names.
    pub fn tick(&self) {
        self.manager.tick();
        self.network.prune();
        self.dispatch();
    }

    /// Runs an API action and delivers what it produced.
    pub fn act(&self, action: impl FnOnce(&PartyManager) -> Outcome) -> Outcome {
        let outcome = action(&self.manager);
        self.dispatch();
        outcome
    }

    /// Leaders of parties who are online: the platform refreshes their size limit (permissions may change).
    pub fn online_leaders(&self) -> Vec<Uuid> {
        self.manager.parties().iter().map(|p| p.leader).filter(|id| self.network.is_online(*id)).collect()
    }

    /// `party` as the API shows it, with the server (world) its leader is on.
    pub fn party_info(&self, party: &Party) -> bridge::PartyInfo {
        bridge::PartyInfo {
            id: party.id,
            leader: party.leader,
            members: party.member_ids(),
            server: self.network.server_of(party.leader),
        }
    }

    /// What the calls since the last drain produced.
    pub fn drain_effects(&self) -> Vec<Effect> {
        self.dispatch();
        std::mem::take(&mut *lock(&self.effects))
    }

    fn effect(&self, effect: Effect) {
        lock(&self.effects).push(effect);
    }

    /// Sends `text` to `to`, after the events published so far (the order the Java plugin delivers them in).
    fn reply(&self, to: Uuid, text: Text) {
        self.dispatch();
        self.effect(Effect::Message { to, text });
    }

    fn names(&self, players: &[(&'static str, Uuid)]) -> Vec<(&'static str, String)> {
        players.iter().map(|(tag, id)| (*tag, self.network.name_of(*id).unwrap_or_else(|| "?".to_owned()))).collect()
    }

    /// `EventDispatcher`: applies the manager's events to the players on this server.
    fn dispatch(&self) {
        loop {
            let events = std::mem::take(&mut *lock(&self.events));
            if events.is_empty() {
                return;
            }
            for event in events {
                self.apply(event);
            }
        }
    }

    fn apply(&self, event: PartyEvent) {
        match event {
            PartyEvent::Notice { recipients, key, players, values } => {
                let mut rendered = None; // most notices only matter to a few players
                for to in recipients.into_iter().filter(|id| self.network.is_online(*id)) {
                    let text =
                        rendered.get_or_insert_with(|| self.messages.render(key, &self.names(&players), &values, &[]));
                    self.effect(Effect::Message { to, text: text.clone() });
                }
            }
            PartyEvent::Chat { recipients, sender, owner, message, mentioned, sent_at } => {
                let line = (sender, owner, message.as_str(), mentioned.as_slice(), sent_at);
                let (mut rendered, mut action_bar) = (None, None);
                let chat = &self.config.chat;
                for to in recipients.iter().copied().filter(|id| self.network.is_online(*id)) {
                    let text = rendered.get_or_insert_with(|| self.render_chat(line, "chat.format"));
                    self.effect(Effect::Message { to, text: text.clone() });
                    if !mentioned.contains(&to) {
                        continue;
                    }
                    let bar = action_bar.get_or_insert_with(|| {
                        self.messages.render("chat.mention-actionbar", &self.names(&[("player", sender)]), &[], &[])
                    });
                    self.effect(Effect::ActionBar { to, text: bar.clone() });
                    if !chat.mention_sound.is_empty() {
                        let sound = chat.mention_sound.clone();
                        self.effect(Effect::Sound { to, sound, volume: chat.volume, pitch: chat.pitch });
                    }
                }
                let mut spied = None;
                for to in self.spies() {
                    if recipients.contains(&to) || !self.network.is_online(to) {
                        continue;
                    }
                    let text = spied.get_or_insert_with(|| self.render_chat(line, "chat.spy"));
                    self.effect(Effect::SpyMessage { to, text: text.clone() });
                }
            }
            PartyEvent::Warp { members, server, leader, delay, skip_if_there } => {
                self.effect(Effect::Warp { members, leader, server, delay, skip_if_there });
            }
            PartyEvent::PartyChanged { affected, party, previous } => {
                for id in affected.into_iter().filter(|id| self.network.is_online(*id)) {
                    if party.as_ref().is_some_and(|p| p.is_member(id)) {
                        continue;
                    }
                    self.unlock(id);
                    if let Some(previous) = &previous {
                        self.effect(Effect::Left { player: id, party: previous.clone() });
                    }
                }
            }
        }
    }

    fn render_chat(&self, line: (Uuid, Uuid, &str, &[Uuid], Millis), key: &str) -> Text {
        let (sender, owner, message, mentioned, sent_at) = line;
        let players = self.names(&[("player", sender), ("owner", owner)]);
        let time = self.config.chat.timestamps.format(sent_at);
        let message = self.highlighted(message, mentioned);
        self.messages.render(key, &players, &[("time", time)], &[("message", message)])
    }

    /// The raw text (never parsed) with each `@name` of a mentioned member styled as `chat.mention`.
    fn highlighted(&self, text: &str, mentioned: &[Uuid]) -> Text {
        if mentioned.is_empty() {
            return Text::plain(text);
        }
        let names: Vec<String> = mentioned.iter().filter_map(|id| self.network.name_of(*id)).collect();
        let mut out = Text::default();
        let mut plain_from = 0;
        for mention in mentions(text) {
            let Some(name) = names.iter().find(|n| n.eq_ignore_ascii_case(&mention.name)) else { continue };
            out.append(Text::plain(&text[plain_from..mention.start]));
            out.append(self.messages.render("chat.mention", &[("player", name.clone())], &[], &[]));
            plain_from = mention.end;
        }
        out.append(Text::plain(&text[plain_from..]));
        out
    }
}
