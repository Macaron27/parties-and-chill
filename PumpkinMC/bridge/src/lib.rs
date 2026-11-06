//! Parties & Chill backend bridge for Pumpkin: the Paper bridge (`BridgePlugin`, `PartyCache`, `BridgeApi`,
//! `Sounds`) for Pumpkin servers behind Velocity or BungeeCord proxies running Parties & Chill. Party state lives
//! on the proxy; this plugin
//!
//! - says hello when a player joins (so the proxy knows this server runs the bridge, its protocol and which events
//!   other plugins listen to), and caches the party snapshots the proxy pushes back;
//! - diverts the chat of players with the party chat lock to the proxy (the proxy can't cancel signed chat);
//! - plays party chat mention sounds;
//! - serves the developer API and its events to other Pumpkin plugins over IPC (see README.md).
//!
//! Like the Paper bridge, it trusts `pnc:main` messages: only proxies that block that channel from clients (the
//! Parties & Chill proxy plugins do) may connect to this server.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex, MutexGuard, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

use parties_core::bridge::{self, BridgeMessage, CHANNEL, PartyInfo};
use pumpkin_plugin_api::events::{
    EventHandler, EventPriority, PlayerChatEvent, PlayerCustomPayloadEvent, PlayerJoinEvent, PlayerLeaveEvent,
    PlayerRegisterChannelEvent,
};
use pumpkin_plugin_api::events_wit::{
    PlayerChatEventData, PlayerCustomPayloadEventData, PlayerJoinEventData, PlayerLeaveEventData,
    PlayerRegisterChannelEventData,
};
use pumpkin_plugin_api::scheduler::{cancel_task, schedule_delayed_task};
use pumpkin_plugin_api::wit::pumpkin::plugin::sounds::SoundCategory;
use pumpkin_plugin_api::{Context, Player, Plugin, PluginMetadata, Server, ipc, register_plugin};
use serde_json::{Map, Value, json};
use uuid::Uuid;

/// The Paper bridge is `PartiesAndChillBridge`; Pumpkin plugin names are lowercase.
const NAME: &str = "partiesandchillbridge";
/// 5 s: generous for a round trip, short enough to surface an outdated or missing proxy plugin.
const TIMEOUT_TICKS: u64 = 100;
const EVENTS: [(&str, u8); 5] = [
    ("PartyCreateEvent", bridge::EVENT_CREATE),
    ("PartyJoinEvent", bridge::EVENT_JOIN),
    ("PartyDisbandEvent", bridge::EVENT_DISBAND),
    ("PartyChatEvent", bridge::EVENT_CHAT),
    ("PartyLeaveEvent", bridge::EVENT_LEAVE),
];

fn uuid(id: pumpkin_plugin_api::uuid::Uuid) -> Uuid {
    Uuid::from_u64_pair(id.high, id.low)
}

fn wit_uuid(id: Uuid) -> pumpkin_plugin_api::uuid::Uuid {
    let (high, low) = id.as_u64_pair();
    pumpkin_plugin_api::uuid::Uuid { high, low }
}

fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// A player's party as the proxy sees it (`PartyCache.PartyView`).
struct PartyView {
    leader: Uuid,
    members: Vec<Uuid>,
}

/// An API call waiting for the proxy's reply.
struct Pending {
    plugin: String,
    action: u8,
    timeout: u32,
}

struct Subscriber {
    plugin: String,
    /// `None`: every event.
    events: Option<HashSet<String>>,
}

#[derive(Default)]
struct State {
    parties: HashMap<Uuid, PartyView>,
    chat_locked: HashSet<Uuid>,
    /// Mutes reported by other plugins (the AdvancedBan hook's job on Paper), re-sent when the player joins.
    mutes: HashMap<Uuid, i64>,
    pending: HashMap<i32, Pending>,
    next_id: i32,
    subscribers: Vec<Subscriber>,
    unknown_sounds: HashSet<String>,
}

struct Bridge {
    state: Mutex<State>,
}

static BRIDGE: RwLock<Option<Arc<Bridge>>> = RwLock::new(None);

fn bridge() -> Option<Arc<Bridge>> {
    BRIDGE.read().unwrap_or_else(|e| e.into_inner()).clone()
}

/// Sends `message` to the proxy through `player`'s connection.
fn send(player: &Player, message: &BridgeMessage) {
    if let Some(java) = player.as_java() {
        java.send_custom_payload(CHANNEL, &message.encode());
    }
}

fn party_json(party: &PartyInfo) -> Value {
    json!({
        "id": party.id.to_string(),
        "leader": party.leader.to_string(),
        "members": party.members.iter().map(Uuid::to_string).collect::<Vec<_>>(),
        "server": party.server,
    })
}

/// Sends `message` to `plugin`, returning its JSON answer (no lock may be held: the plugin may call back).
fn tell(plugin: &str, message: &Value) -> Option<Value> {
    let bytes = serde_json::to_vec(message).ok()?;
    match ipc::send_ipc_message(plugin, &bytes) {
        Ok(Ok(reply)) => serde_json::from_slice(&reply).ok(),
        Ok(Err(e)) => {
            tracing::warn!("Parties & Chill bridge: plugin {plugin} failed handling a message: {e}");
            None
        }
        Err(()) => None,
    }
}

impl Bridge {
    fn state(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn listeners(&self, event: &str) -> Vec<String> {
        let state = self.state();
        let subscribed = state.subscribers.iter();
        subscribed.filter(|s| s.events.as_ref().is_none_or(|e| e.contains(event))).map(|s| s.plugin.clone()).collect()
    }

    /// `1 << EVENT_*` bits of the events at least one plugin listens to (sent in every hello).
    fn listened_events(&self) -> i32 {
        EVENTS.iter().filter(|(name, _)| !self.listeners(name).is_empty()).fold(0, |bits, (_, code)| bits | 1 << code)
    }

    fn greet(&self, player: &Player) {
        send(player, &BridgeMessage::Hello { protocol: bridge::PROTOCOL, listeners: self.listened_events() });
        let id = uuid(player.get_id());
        let mute = self.state().mutes.get(&id).copied().filter(|until| *until > now());
        if let Some(until) = mute {
            send(player, &BridgeMessage::Mute { player: id, until });
        }
    }

    /// Hands `event` to each listener in turn; `true` if one cancelled it.
    fn cancelled(&self, name: &str, mut event: Map<String, Value>) -> bool {
        event.insert("event".to_owned(), json!(name));
        event.insert("cancelled".to_owned(), json!(false));
        for plugin in self.listeners(name) {
            let answer = tell(&plugin, &Value::Object(event.clone()));
            if let Some(cancelled) = answer.as_ref().and_then(|a| a.get("cancelled")).and_then(Value::as_bool) {
                event.insert("cancelled".to_owned(), json!(cancelled));
            }
        }
        event.get("cancelled").and_then(Value::as_bool).unwrap_or(false)
    }

    /// A message the proxy sent through `carrier`'s connection.
    fn receive(&self, server: &Server, carrier: &Player, data: &[u8]) {
        let message = match BridgeMessage::decode(data) {
            Ok(message) => message,
            Err(e) => {
                tracing::warn!("Parties & Chill bridge: ignoring malformed message from the proxy: {e}");
                return;
            }
        };
        match message {
            BridgeMessage::Snapshot { player, leader, members } => {
                let mut state = self.state();
                match leader {
                    Some(leader) => state.parties.insert(player, PartyView { leader, members }),
                    None => state.parties.remove(&player),
                };
            }
            BridgeMessage::ChatLock { player, locked } => {
                let mut state = self.state();
                if locked {
                    state.chat_locked.insert(player);
                } else {
                    state.chat_locked.remove(&player);
                }
            }
            BridgeMessage::Reply { id, status, party } => self.complete(id, status, party),
            BridgeMessage::Check { id, event, player, party, message } => {
                // Checks travel on the connection of the player they are about: the verdict goes back on it.
                let allowed = match EVENTS.iter().find(|(_, code)| *code == event) {
                    None => true, // from a newer proxy: nobody here can cancel it
                    Some((name, _)) => {
                        let mut payload = Map::new();
                        payload.insert("player".to_owned(), json!(player.to_string()));
                        payload.insert("party".to_owned(), party_json(&party));
                        if event == bridge::EVENT_CHAT {
                            payload.insert("message".to_owned(), json!(message));
                        }
                        !self.cancelled(name, payload)
                    }
                };
                send(carrier, &BridgeMessage::Verdict { id, allowed });
            }
            BridgeMessage::Left { player, party } => {
                for plugin in self.listeners("PartyLeaveEvent") {
                    let event =
                        json!({"event": "PartyLeaveEvent", "player": player.to_string(), "party": party_json(&party)});
                    tell(&plugin, &event);
                }
            }
            BridgeMessage::Sound { player, sound, volume, pitch } => {
                let Some(target) = server.get_player_by_uuid(wit_uuid(player)) else { return };
                match parties_core::sound::key(&sound) {
                    Some(key) => target.play_custom_sound(&key, SoundCategory::Master, volume, pitch),
                    None => {
                        if self.state().unknown_sounds.insert(sound.clone()) {
                            tracing::warn!(
                                "Parties & Chill bridge: unknown sound '{sound}' (chat.mention.sound in the proxy's \
                                 config.yml); use a namespaced key"
                            );
                        }
                    }
                }
            }
            BridgeMessage::Hello { .. }
            | BridgeMessage::Chat { .. }
            | BridgeMessage::Mute { .. }
            | BridgeMessage::Request { .. }
            | BridgeMessage::Verdict { .. } => {
                tracing::debug!("Parties & Chill bridge: ignoring a proxy-bound message")
            }
        }
    }

    /// Answers the plugin that made request `id`; replies to requests that already timed out are dropped.
    fn complete(&self, id: i32, status: u8, party: Option<PartyInfo>) {
        let Some(pending) = self.state().pending.remove(&id) else { return };
        cancel_task(pending.timeout);
        let reply = if status == bridge::STATUS_ERROR {
            json!({"reply": id, "ok": false, "error": "proxy_error", "message": "the proxy failed to process the request (see its log)"})
        } else if pending.action == bridge::ACTION_GET {
            json!({"reply": id, "ok": true, "result": party.as_ref().map(party_json)})
        } else {
            json!({"reply": id, "ok": true, "result": status == bridge::STATUS_SUCCESS})
        };
        tell(&pending.plugin, &reply);
    }

    fn fail(&self, id: i32, error: &str, message: &str) {
        let Some(pending) = self.state().pending.remove(&id) else { return };
        cancel_task(pending.timeout);
        tell(&pending.plugin, &json!({"reply": id, "ok": false, "error": error, "message": message}));
    }

    /// Sends an API request through a connection: the player it is about if they are here (the proxy can only ask
    /// the backend of players it hosts), else the other one, else anyone. `None` if nobody can carry it.
    fn request(&self, server: &Server, plugin: &str, action: u8, actor: Uuid, target: Uuid) -> Option<i32> {
        let subject = if action == bridge::ACTION_ADD { target } else { actor };
        let other = if subject == actor { target } else { actor };
        let carrier = server
            .get_player_by_uuid(wit_uuid(subject))
            .or_else(|| server.get_player_by_uuid(wit_uuid(other)))
            .or_else(|| server.get_all_players().into_iter().next())?;
        let id = {
            let mut state = self.state();
            state.next_id = state.next_id.wrapping_add(1);
            state.next_id
        };
        let timeout = schedule_delayed_task(TIMEOUT_TICKS, move |_| {
            if let Some(bridge) = bridge() {
                bridge.fail(id, "timeout", "the proxy didn't answer within 5 s (does it run Parties & Chill 1.1+?)");
            }
        });
        self.state().pending.insert(id, Pending { plugin: plugin.to_owned(), action, timeout });
        send(&carrier, &BridgeMessage::Request { id, action, actor, target });
        Some(id)
    }

    /// Reports a mute to the proxy through any online player; with nobody online it is re-sent when the muted
    /// player next joins.
    fn report_mute(&self, server: &Server, player: Uuid, until: i64) {
        {
            let mut state = self.state();
            if until > now() {
                state.mutes.insert(player, until);
            } else {
                state.mutes.remove(&player);
            }
        }
        if let Some(carrier) = server.get_all_players().into_iter().next() {
            send(&carrier, &BridgeMessage::Mute { player, until: until.max(0) });
        }
    }
}

fn ok(result: Value) -> Value {
    json!({"ok": true, "result": result})
}

fn error(kind: &str, message: impl Into<String>) -> Value {
    json!({"ok": false, "error": kind, "message": message.into()})
}

fn player_arg(request: &Map<String, Value>, key: &str) -> Result<Uuid, Value> {
    let text =
        request.get(key).and_then(Value::as_str).ok_or_else(|| error("bad_request", format!("missing \"{key}\"")))?;
    Uuid::parse_str(text).map_err(|_| error("bad_request", format!("\"{key}\" is not a UUID")))
}

/// The API over IPC: `{"method": ..., ...}`. Calls the proxy answers (`getParty`, `createParty`, `addMember`,
/// `removeMember`, `disbandParty`) reply `{"ok": true, "result": {"request": <id>}}` at once and the answer comes
/// later as an IPC message `{"reply": <id>, "ok": ..., "result" | "error": ...}`.
fn handle(server: Option<&Server>, sender: &str, request: &Map<String, Value>) -> Result<Value, Value> {
    let Some(bridge) = bridge() else { return Ok(error("unavailable", "the bridge is not running")) };
    let method = request.get("method").and_then(Value::as_str).unwrap_or("");
    let asynchronous = |action: u8, actor: Uuid, target: Uuid| {
        let Some(server) = server else { return Ok(error("unavailable", "the server is not running")) };
        match bridge.request(server, sender, action, actor, target) {
            Some(id) => Ok(ok(json!({"request": id}))),
            None => Ok(error("unavailable", "no player online on this server to carry the request to the proxy")),
        }
    };
    match method {
        "getParty" => {
            let id = player_arg(request, "player")?;
            asynchronous(bridge::ACTION_GET, id, id)
        }
        "createParty" => {
            let leader = player_arg(request, "leader")?;
            asynchronous(bridge::ACTION_CREATE, leader, leader)
        }
        "addMember" => asynchronous(bridge::ACTION_ADD, player_arg(request, "leader")?, player_arg(request, "target")?),
        "removeMember" => {
            asynchronous(bridge::ACTION_REMOVE, player_arg(request, "leader")?, player_arg(request, "target")?)
        }
        "disbandParty" => {
            let leader = player_arg(request, "leader")?;
            asynchronous(bridge::ACTION_DISBAND, leader, leader)
        }
        "isInParty" => Ok(ok(json!(bridge.state().parties.contains_key(&player_arg(request, "player")?)))),
        "isPartyLeader" => {
            let id = player_arg(request, "player")?;
            Ok(ok(json!(bridge.state().parties.get(&id).is_some_and(|p| p.leader == id))))
        }
        "getCachedParty" => {
            // The 1.0 PartyCache service: what the proxy pushed for a player on this server.
            let id = player_arg(request, "player")?;
            let state = bridge.state();
            Ok(ok(state.parties.get(&id).map_or(Value::Null, |p| {
                json!({"leader": p.leader.to_string(), "members": p.members.iter().map(Uuid::to_string).collect::<Vec<_>>()})
            })))
        }
        "setMuted" => {
            let id = player_arg(request, "player")?;
            let until = request
                .get("until")
                .and_then(Value::as_i64)
                .ok_or_else(|| error("bad_request", "missing \"until\""))?;
            match server {
                Some(server) => bridge.report_mute(server, id, until),
                None => return Ok(error("unavailable", "the server is not running")),
            }
            Ok(ok(json!(true)))
        }
        "subscribe" => {
            let events = match request.get("events") {
                None | Some(Value::Null) => None,
                Some(Value::Array(names)) => {
                    let names: HashSet<String> = names.iter().filter_map(Value::as_str).map(str::to_owned).collect();
                    if let Some(unknown) = names.iter().find(|n| !EVENTS.iter().any(|(e, _)| e == n)) {
                        return Err(error("bad_request", format!("unknown event {unknown}")));
                    }
                    Some(names)
                }
                Some(_) => return Err(error("bad_request", "\"events\" must be a list")),
            };
            let mut state = bridge.state();
            state.subscribers.retain(|s| s.plugin != sender);
            state.subscribers.push(Subscriber { plugin: sender.to_owned(), events });
            Ok(ok(json!(true)))
        }
        "unsubscribe" => {
            bridge.state().subscribers.retain(|s| s.plugin != sender);
            Ok(ok(json!(true)))
        }
        _ => Err(error("bad_request", format!("unknown method \"{method}\""))),
    }
}

thread_local! {
    // API calls arrive without a server handle. A plugin runs on one thread.
    static SERVER: std::cell::RefCell<Option<Server>> = const { std::cell::RefCell::new(None) };
}

struct Join;

impl EventHandler<PlayerJoinEvent> for Join {
    fn handle(&self, _: Server, event: PlayerJoinEventData) -> PlayerJoinEventData {
        if let Some(bridge) = bridge() {
            bridge.greet(&event.player);
        }
        event
    }
}

/// Usually the proxy registered the channel already; otherwise the client's registration greets again.
struct RegisterChannel;

impl EventHandler<PlayerRegisterChannelEvent> for RegisterChannel {
    fn handle(&self, _: Server, event: PlayerRegisterChannelEventData) -> PlayerRegisterChannelEventData {
        if event.channel == CHANNEL
            && let Some(bridge) = bridge()
        {
            bridge.greet(&event.player);
        }
        event
    }
}

struct Leave;

impl EventHandler<PlayerLeaveEvent> for Leave {
    fn handle(&self, _: Server, event: PlayerLeaveEventData) -> PlayerLeaveEventData {
        if let Some(bridge) = bridge() {
            let id = uuid(event.player.get_id());
            let mut state = bridge.state();
            state.parties.remove(&id);
            state.chat_locked.remove(&id);
        }
        event
    }
}

/// Diverts the chat of players with the party chat lock before anyone broadcasts it.
struct Chat;

impl EventHandler<PlayerChatEvent> for Chat {
    fn handle(&self, _: Server, mut event: PlayerChatEventData) -> PlayerChatEventData {
        if event.cancelled {
            return event;
        }
        let Some(bridge) = bridge() else { return event };
        let id = uuid(event.player.get_id());
        let (locked, muted_until) = {
            let state = bridge.state();
            (state.chat_locked.contains(&id), state.mutes.get(&id).copied().filter(|u| *u > now()).unwrap_or(0))
        };
        if locked {
            event.cancelled = true;
            send(&event.player, &BridgeMessage::Chat { message: event.message.clone(), muted_until });
        }
        event
    }
}

struct Payload;

impl EventHandler<PlayerCustomPayloadEvent> for Payload {
    fn handle(&self, server: Server, event: PlayerCustomPayloadEventData) -> PlayerCustomPayloadEventData {
        if event.channel == CHANNEL
            && let Some(bridge) = bridge()
        {
            bridge.receive(&server, &event.player, &event.data);
        }
        event
    }
}

struct BridgePlugin;

impl Plugin for BridgePlugin {
    fn new() -> Self {
        BridgePlugin
    }

    fn metadata(&self) -> PluginMetadata {
        PluginMetadata {
            name: NAME.into(),
            version: env!("CARGO_PKG_VERSION").into(),
            authors: vec!["Parties & Chill".into()],
            description: "Backend bridge for Parties & Chill (party chat lock, mention sounds, developer API)".into(),
            dependencies: vec![],
            permissions: vec![],
        }
    }

    fn on_load(&self, context: Context) -> pumpkin_plugin_api::Result<()> {
        // Random start: a reply meant for the instance before a reload must not match a new request.
        let state = State { next_id: Uuid::new_v4().as_u64_pair().0 as i32, ..State::default() };
        *BRIDGE.write().unwrap_or_else(|e| e.into_inner()) = Some(Arc::new(Bridge { state: Mutex::new(state) }));
        SERVER.with(|s| *s.borrow_mut() = Some(context.get_server()));
        context.register_event_handler(Join, EventPriority::Normal, false)?;
        context.register_event_handler(RegisterChannel, EventPriority::Normal, false)?;
        context.register_event_handler(Leave, EventPriority::Normal, false)?;
        // Blocking: the chat must be cancelled before it is broadcast; payloads keep their order.
        context.register_event_handler(Chat, EventPriority::Highest, true)?;
        context.register_event_handler(Payload, EventPriority::Normal, true)?;
        // Loaded while players are online (reload): greet them so the proxy re-sends their snapshots.
        if let Some(bridge) = bridge() {
            for player in context.get_server().get_all_players() {
                bridge.greet(&player);
            }
        }
        tracing::info!("Parties & Chill bridge ready (protocol {}, channel {CHANNEL})", bridge::PROTOCOL);
        Ok(())
    }

    fn on_unload(&self, _context: Context) -> pumpkin_plugin_api::Result<()> {
        if let Some(bridge) = BRIDGE.write().unwrap_or_else(|e| e.into_inner()).take() {
            let pending: Vec<i32> = bridge.state().pending.keys().copied().collect();
            for id in pending {
                bridge.fail(id, "cancelled", "Parties & Chill bridge was disabled");
            }
        }
        SERVER.with(|s| *s.borrow_mut() = None);
        Ok(())
    }

    fn handle_ipc_message(&self, sender: String, message: Vec<u8>) -> pumpkin_plugin_api::Result<Vec<u8>, String> {
        let reply = match serde_json::from_slice::<Value>(&message) {
            Ok(Value::Object(request)) => {
                SERVER.with(|s| handle(s.borrow().as_ref(), &sender, &request)).unwrap_or_else(|e| e)
            }
            _ => error("bad_request", "expected a JSON object"),
        };
        Ok(serde_json::to_vec(&reply).unwrap_or_default())
    }
}

register_plugin!(BridgePlugin);
