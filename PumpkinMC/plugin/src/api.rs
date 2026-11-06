//! The developer API (Java's `NetworkPartiesAPI` and its Bukkit events) over Pumpkin's plugin-to-plugin messages:
//! JSON in UTF-8, so plugins in any language can use it. See README.md for the reference.
//!
//! Requests: `{"method": "getParty", "player": "<uuid>"}` and so on, one per API method; replies are
//! `{"ok": true, "result": ...}` or `{"ok": false, "error": "...", "message": "..."}`. `{"method": "subscribe"}` makes
//! Parties & Chill send the sender its events (`{"event": "PartyJoinEvent", "player": ..., "party": {...},
//! "cancelled": false}`); answering `{"cancelled": true}` cancels a cancellable one. Subscribers are asked in turn,
//! each seeing the verdict so far, and only while no lock is held: they may call the API back.

use std::collections::HashSet;
use std::sync::{Arc, Mutex};

use parties_core::bridge::PartyInfo;
use parties_core::{Action, Outcome, Party, PartyGuard};
use pumpkin_plugin_api::ipc;
use serde_json::{Map, Value, json};
use uuid::Uuid;

use crate::runtime;

const EVENTS: [&str; 5] =
    ["PartyCreateEvent", "PartyJoinEvent", "PartyDisbandEvent", "PartyChatEvent", "PartyLeaveEvent"];

fn event_name(action: Action) -> &'static str {
    match action {
        Action::Create => "PartyCreateEvent",
        Action::Join => "PartyJoinEvent",
        Action::Disband => "PartyDisbandEvent",
        Action::Chat => "PartyChatEvent",
    }
}

struct Subscriber {
    plugin: String,
    /// `None`: every event.
    events: Option<HashSet<String>>,
}

#[derive(Default)]
pub(crate) struct Subscribers(Mutex<Vec<Subscriber>>);

impl Subscribers {
    fn subscribe(&self, plugin: &str, events: Option<HashSet<String>>) {
        let mut list = self.0.lock().unwrap_or_else(|e| e.into_inner());
        list.retain(|s| s.plugin != plugin);
        list.push(Subscriber { plugin: plugin.to_owned(), events });
    }

    fn unsubscribe(&self, plugin: &str) {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).retain(|s| s.plugin != plugin);
    }

    /// Plugins listening to `event`, copied so no lock is held while they are called.
    fn listeners(&self, event: &str) -> Vec<String> {
        let list = self.0.lock().unwrap_or_else(|e| e.into_inner());
        list.iter().filter(|s| s.events.as_ref().is_none_or(|e| e.contains(event))).map(|s| s.plugin.clone()).collect()
    }

    /// Hands `event` to each listener in turn and returns it with their verdicts.
    fn call(&self, name: &str, mut event: Map<String, Value>) -> Map<String, Value> {
        for plugin in self.listeners(name) {
            let Ok(bytes) = serde_json::to_vec(&event) else { return event };
            match ipc::send_ipc_message(&plugin, &bytes) {
                Ok(Ok(reply)) => {
                    if let Ok(Value::Object(answer)) = serde_json::from_slice::<Value>(&reply)
                        && let Some(cancelled) = answer.get("cancelled").filter(|v| v.is_boolean())
                        && event.contains_key("cancelled")
                    {
                        event.insert("cancelled".to_owned(), cancelled.clone());
                    }
                }
                Ok(Err(e)) => tracing::warn!("Parties & Chill: plugin {plugin} failed handling {name}: {e}"),
                Err(()) => tracing::debug!("Parties & Chill: plugin {plugin} is unavailable"),
            }
        }
        event
    }

    /// `PartyLeaveEvent`: `player` is no longer in `party` (shown as it was before). Not cancellable.
    pub fn left(&self, player: Uuid, party: &Party) {
        if self.listeners("PartyLeaveEvent").is_empty() {
            return;
        }
        let mut event = Map::new();
        event.insert("event".to_owned(), json!("PartyLeaveEvent"));
        event.insert("player".to_owned(), json!(player.to_string()));
        event.insert("party".to_owned(), party_json(&info(party)));
        self.call("PartyLeaveEvent", event);
    }
}

/// The cancellable events, as the core's guard.
pub(crate) struct IpcGuard(pub Arc<Subscribers>);

impl PartyGuard for IpcGuard {
    fn watches(&self, action: Action, _: Uuid) -> bool {
        !self.0.listeners(event_name(action)).is_empty()
    }

    fn allows(&self, action: Action, player: Uuid, party: &Party, message: &str) -> bool {
        let name = event_name(action);
        let mut event = Map::new();
        event.insert("event".to_owned(), json!(name));
        event.insert("player".to_owned(), json!(player.to_string()));
        event.insert("party".to_owned(), party_json(&info(party)));
        if action == Action::Chat {
            event.insert("message".to_owned(), json!(message));
        }
        event.insert("cancelled".to_owned(), json!(false));
        !self.0.call(name, event).get("cancelled").and_then(Value::as_bool).unwrap_or(false)
    }
}

/// `party` with the world its leader is in (the API's `Party#getServer`).
fn info(party: &Party) -> PartyInfo {
    match runtime() {
        Some(rt) => rt.core.party_info(party),
        None => PartyInfo { id: party.id, leader: party.leader, members: party.member_ids(), server: None },
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

fn ok(result: Value) -> Value {
    json!({"ok": true, "result": result})
}

fn error(kind: &str, message: impl Into<String>) -> Value {
    json!({"ok": false, "error": kind, "message": message.into()})
}

fn player(request: &Map<String, Value>, key: &str) -> Result<Uuid, Value> {
    let text =
        request.get(key).and_then(Value::as_str).ok_or_else(|| error("bad_request", format!("missing \"{key}\"")))?;
    Uuid::parse_str(text).map_err(|_| error("bad_request", format!("\"{key}\" is not a UUID")))
}

/// Answers one request from plugin `sender`.
pub(crate) fn handle(sender: &str, message: &[u8]) -> Vec<u8> {
    let reply = match serde_json::from_slice::<Value>(message) {
        Ok(Value::Object(request)) => dispatch(sender, &request).unwrap_or_else(|e| e),
        _ => error("bad_request", "expected a JSON object"),
    };
    serde_json::to_vec(&reply).unwrap_or_default()
}

fn dispatch(sender: &str, request: &Map<String, Value>) -> Result<Value, Value> {
    let Some(rt) = runtime() else { return Ok(error("unavailable", "Parties & Chill is not running")) };
    let method = request.get("method").and_then(Value::as_str).unwrap_or("");
    let change = |outcome: Outcome| {
        rt.deliver_anywhere();
        Ok(ok(json!(outcome == Outcome::Success)))
    };
    match method {
        "getParty" => {
            let id = player(request, "player")?;
            Ok(ok(rt.core.manager().party_of(id).map_or(Value::Null, |p| party_json(&rt.core.party_info(&p)))))
        }
        "isInParty" => Ok(ok(json!(rt.core.manager().party_of(player(request, "player")?).is_some()))),
        "isPartyLeader" => {
            let id = player(request, "player")?;
            Ok(ok(json!(rt.core.manager().party_of(id).is_some_and(|p| p.is_leader(id)))))
        }
        "createParty" => {
            let leader = player(request, "leader")?;
            change(rt.core.act(|m| m.create(leader)))
        }
        "addMember" => {
            let (leader, target) = (player(request, "leader")?, player(request, "target")?);
            change(rt.core.act(|m| m.add(leader, target)))
        }
        "removeMember" => {
            let (leader, target) = (player(request, "leader")?, player(request, "target")?);
            change(rt.core.act(|m| m.kick(leader, target)))
        }
        "disbandParty" => {
            let leader = player(request, "leader")?;
            change(rt.core.act(|m| m.disband(leader)))
        }
        "setMuted" => {
            // The AdvancedBan hook's job on Paper: a moderation plugin reports mutes (epoch ms, 0 lifts).
            let id = player(request, "player")?;
            let until = request
                .get("until")
                .and_then(Value::as_i64)
                .ok_or_else(|| error("bad_request", "missing \"until\""))?;
            rt.core.network().set_muted_until(id, until);
            Ok(ok(json!(true)))
        }
        "subscribe" => {
            let events = match request.get("events") {
                None | Some(Value::Null) => None,
                Some(Value::Array(names)) => {
                    let names: HashSet<String> = names.iter().filter_map(Value::as_str).map(str::to_owned).collect();
                    if let Some(unknown) = names.iter().find(|n| !EVENTS.contains(&n.as_str())) {
                        return Err(error("bad_request", format!("unknown event {unknown}")));
                    }
                    Some(names)
                }
                Some(_) => return Err(error("bad_request", "\"events\" must be a list")),
            };
            rt.api.subscribe(sender, events);
            Ok(ok(json!(true)))
        }
        "unsubscribe" => {
            rt.api.unsubscribe(sender);
            Ok(ok(json!(true)))
        }
        _ => Err(error("bad_request", format!("unknown method \"{method}\""))),
    }
}
