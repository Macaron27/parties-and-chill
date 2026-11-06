//! Test plugin for Parties & Chill's API (end-to-end tests only; not shipped). It subscribes to the events of
//! whichever Parties & Chill plugin runs (`partiesandchill` or `partiesandchillbridge`), logs them, cancels joins of a
//! player named Dave, calls the API back from inside a chat event whose line contains "api", logs teleports (warps)
//! and forwards `/probe <pnc|bridge> <json>` requests, logging every answer as `probe: ...`.

use pumpkin_plugin_api::command::{
    Arg, ArgumentType, Command, CommandError, CommandNode, CommandSender, ConsumedArgs, StringType,
};
use pumpkin_plugin_api::commands::CommandHandler;
use pumpkin_plugin_api::events::{EventHandler, EventPriority, PlayerTeleportEvent};
use pumpkin_plugin_api::events_wit::PlayerTeleportEventData;
use pumpkin_plugin_api::permission::{Permission, PermissionDefault};
use pumpkin_plugin_api::scheduler::schedule_delayed_task;
use pumpkin_plugin_api::text::TextComponent;
use pumpkin_plugin_api::{Context, Plugin, PluginMetadata, Server, ipc, register_plugin};
use serde_json::{Value, json};

const TARGETS: [&str; 2] = ["partiesandchill", "partiesandchillbridge"];

fn send(plugin: &str, request: &Value) -> String {
    match ipc::send_ipc_message(plugin, request.to_string().as_bytes()) {
        Ok(Ok(reply)) => String::from_utf8_lossy(&reply).into_owned(),
        Ok(Err(e)) => format!("error: {e}"),
        Err(()) => "unavailable".to_owned(),
    }
}

struct Probe;

impl CommandHandler for Probe {
    fn handle(&self, sender: CommandSender, server: Server, args: ConsumedArgs) -> Result<i32, CommandError> {
        let line = match args.get_value("args") {
            Arg::Simple(s) | Arg::Msg(s) => s,
            _ => String::new(),
        };
        let (target, json) = line.split_once(' ').unwrap_or((&line, "{}"));
        let plugin = if target == "bridge" { TARGETS[1] } else { TARGETS[0] };
        let reply = match serde_json::from_str::<Value>(json) {
            Ok(mut request) => {
                // "@Name" values become that online player's UUID (Pumpkin's offline UUIDs aren't vanilla's).
                if let Value::Object(fields) = &mut request {
                    for value in fields.values_mut() {
                        if let Some(name) = value.as_str().and_then(|v| v.strip_prefix('@'))
                            && let Some(player) = server.get_player_by_name(name)
                        {
                            *value = json!(uuid_string(player.get_id()));
                        }
                    }
                }
                send(plugin, &request)
            }
            Err(e) => format!("bad json: {e}"),
        };
        tracing::info!("probe: {plugin} answered {reply}");
        sender.send_message(TextComponent::text(&format!("probe: {reply}")));
        Ok(1)
    }
}

struct Teleport;

impl EventHandler<PlayerTeleportEvent> for Teleport {
    fn handle(&self, _: Server, event: PlayerTeleportEventData) -> PlayerTeleportEventData {
        tracing::info!("probe: teleport {}", event.player.get_name());
        event
    }
}

struct ProbePlugin;

impl Plugin for ProbePlugin {
    fn new() -> Self {
        ProbePlugin
    }

    fn metadata(&self) -> PluginMetadata {
        PluginMetadata {
            name: "pncprobe".into(),
            version: env!("CARGO_PKG_VERSION").into(),
            authors: vec!["Parties & Chill".into()],
            description: "Parties & Chill API test plugin".into(),
            dependencies: vec![],
            permissions: vec![],
        }
    }

    fn on_load(&self, context: Context) -> pumpkin_plugin_api::Result<()> {
        context.register_permission(&Permission {
            node: "pncprobe:use".to_owned(),
            description: "probe".to_owned(),
            default: PermissionDefault::Allow,
            children: Vec::new(),
        })?;
        let rest = ArgumentType::String(StringType::Greedy);
        context.register_command(
            Command::new(&["probe".to_owned()], "API probe").then(CommandNode::argument("args", &rest).execute(Probe)),
            "pncprobe:use",
        );
        context.register_event_handler(Teleport, EventPriority::Normal, false)?;
        SERVER.with(|s| *s.borrow_mut() = Some(context.get_server()));
        // Once every plugin is loaded (before the first player joins: the bridge's hello carries the listeners).
        schedule_delayed_task(1, |_| {
            for plugin in TARGETS {
                tracing::info!("probe: subscribe to {plugin}: {}", send(plugin, &json!({"method": "subscribe"})));
            }
        });
        Ok(())
    }

    fn handle_ipc_message(&self, sender: String, message: Vec<u8>) -> pumpkin_plugin_api::Result<Vec<u8>, String> {
        let event: Value = serde_json::from_slice(&message).map_err(|e| e.to_string())?;
        tracing::info!("probe: from {sender}: {event}");
        let mut answer = json!({});
        if let Some(name) = event.get("event").and_then(Value::as_str) {
            let player = event["player"].as_str().unwrap_or("");
            if name == "PartyJoinEvent" && name_of(player).as_deref() == Some("Dave") {
                answer = json!({"cancelled": true});
                tracing::info!("probe: cancelled Dave's join");
            }
            if name == "PartyChatEvent" && event["message"].as_str().is_some_and(|m| m.contains("api")) {
                // Calls the API back while the plugin is waiting for this verdict.
                let reply = send(&sender, &json!({"method": "isInParty", "player": player}));
                tracing::info!("probe: re-entrant isInParty -> {reply}");
            }
        }
        Ok(answer.to_string().into_bytes())
    }
}

fn uuid_string(id: pumpkin_plugin_api::uuid::Uuid) -> String {
    let hex = format!("{:016x}{:016x}", id.high, id.low);
    format!("{}-{}-{}-{}-{}", &hex[..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..])
}

thread_local! {
    static SERVER: std::cell::RefCell<Option<Server>> = const { std::cell::RefCell::new(None) };
}

/// The name of the online player with UUID `id` (`xxxxxxxx-xxxx-...`).
fn name_of(id: &str) -> Option<String> {
    let hex: String = id.chars().filter(|c| *c != '-').collect();
    let high = u64::from_str_radix(hex.get(..16)?, 16).ok()?;
    let low = u64::from_str_radix(hex.get(16..)?, 16).ok()?;
    let id = pumpkin_plugin_api::uuid::Uuid { high, low };
    SERVER.with(|s| s.borrow().as_ref().and_then(|server| server.get_player_by_uuid(id)).map(|p| p.get_name()))
}

register_plugin!(ProbePlugin);
