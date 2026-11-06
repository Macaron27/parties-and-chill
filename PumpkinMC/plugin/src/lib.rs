//! Parties & Chill for Pumpkin: the Velocity/BungeeCord plugin's behaviour (`PartiesAndChill`, `VelocityCommand`,
//! `VelocityPlayer`) on a Pumpkin server. Everything else is `parties-core`.
//!
//! What maps to what:
//! - `/party` (`/p`) and `/pchat` (`/pc`, `/party-chat`): Pumpkin commands taking the rest of the line, parsed by
//!   the core as in Java. Permissions `partiesandchill:command.party` / `partiesandchill:command.pchat` (default
//!   allow) stand in for "everyone" (Pumpkin requires a node per command).
//! - Permissions `parties.admin.spy` and the `party.permission-sizes` nodes: the same nodes in Pumpkin's
//!   namespace for this plugin (`partiesandchill:parties.admin.spy`...), as Pumpkin requires.
//! - `PostLoginEvent` / `DisconnectEvent`: `PlayerJoinEvent` / `PlayerLeaveEvent`. `ServerPostConnectEvent`: there is
//!   one server, so a player's "server" is their world: `PlayerChangedWorldEvent` drives auto-warp.
//! - `PlayerChatEvent` (chat lock): `PlayerChatEvent`, cancelled for locked players (a server can drop any chat).
//! - `/p warp` and auto-warp: members are teleported to the leader instead of being sent to a server.
//! - The developer API and its Bukkit events: JSON over IPC, see `api`.

mod api;
mod text;

use std::cell::RefCell;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

use parties_core::{
    BUNDLED_CONFIG, BUNDLED_MESSAGES, Effect, Messages, PARTY_SUBCOMMANDS, PartiesCore, PluginConfig, SPY_PERMISSION,
    Sender, split_args,
};
use pumpkin_plugin_api::command::{
    Arg, ArgumentType, Command, CommandError, CommandNode, CommandSender, ConsumedArgs, StringType,
};
use pumpkin_plugin_api::commands::CommandHandler;
use pumpkin_plugin_api::events::{
    EventHandler, EventPriority, PlayerChangedWorldEvent, PlayerChatEvent, PlayerJoinEvent, PlayerLeaveEvent,
};
use pumpkin_plugin_api::events_wit::{
    PlayerChangedWorldEventData, PlayerChatEventData, PlayerJoinEventData, PlayerLeaveEventData,
};
use pumpkin_plugin_api::permission::{Permission, PermissionDefault, PermissionLevel};
use pumpkin_plugin_api::scheduler::{SchedulerExt, cancel_task, schedule_delayed_task};
use pumpkin_plugin_api::{Context, Player, Plugin, PluginMetadata, Server, permissions, register_plugin};
use uuid::Uuid;

use crate::text::{component, uuid, wit_uuid};

/// The plugin's name (Velocity's plugin id): Pumpkin requires its permissions to live in this namespace.
const NAME: &str = "partiesandchill";
const COMMAND_PARTY: &str = "partiesandchill:command.party";
const COMMAND_PCHAT: &str = "partiesandchill:command.pchat";

/// A Java permission node in this plugin's namespace (`parties.admin.spy` → `partiesandchill:parties.admin.spy`);
/// nodes that already name a namespace are used as they are.
fn node(java: &str) -> String {
    if java.contains(':') { java.to_owned() } else { format!("{NAME}:{java}") }
}

fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

pub(crate) struct Runtime {
    pub core: PartiesCore,
    pub api: Arc<api::Subscribers>,
    spy: String,
    /// Connected players' entity ids: a leave event of a replaced session is ignored.
    sessions: Mutex<HashMap<Uuid, u32>>,
    timer: u32,
}

static RUNTIME: RwLock<Option<Arc<Runtime>>> = RwLock::new(None);

thread_local! {
    // For delivering what an IPC call produced (no server handle comes with it). A plugin runs on one thread.
    static SERVER: RefCell<Option<Server>> = const { RefCell::new(None) };
}

pub(crate) fn runtime() -> Option<Arc<Runtime>> {
    RUNTIME.read().unwrap_or_else(|e| e.into_inner()).clone()
}

/// The world a player is in, standing in for the proxy's backend server.
fn world_of(player: &Player) -> String {
    player.get_world().get_id()
}

impl Runtime {
    /// The party size `player`'s permissions grant (asked outside the core: it calls the host).
    fn size_limit(&self, player: &Player) -> u32 {
        self.core.config().party.size_limit(|permission| player.has_permission(&node(permission)))
    }

    fn sender(&self, player: &Player) -> Sender {
        let id = uuid(player.get_id());
        // The owner's permissions apply at once, as on the proxy: refresh before party rules read them.
        self.core.network().set_size_limit(id, self.size_limit(player));
        Sender {
            id,
            name: player.get_name(),
            server: Some(world_of(player)),
            can_spy: player.has_permission(&self.spy),
        }
    }

    fn join(&self, server: &Server, player: &Player) {
        let id = uuid(player.get_id());
        self.sessions.lock().unwrap_or_else(|e| e.into_inner()).insert(id, player.as_entity().get_id());
        let limit = self.size_limit(player);
        self.core.player_joined(id, &player.get_name(), limit, Some(&world_of(player)));
        self.deliver(server);
    }

    fn leave(&self, server: &Server, player: &Player) {
        let id = uuid(player.get_id());
        {
            let mut sessions = self.sessions.lock().unwrap_or_else(|e| e.into_inner());
            if sessions.get(&id) != Some(&player.as_entity().get_id()) {
                return; // replaced by a newer login of the same player
            }
            sessions.remove(&id);
        }
        self.core.player_left(id);
        self.deliver(server);
    }

    /// The 1 s sweep. Owners' size limits are refreshed first: permissions may have changed.
    fn tick(&self, server: &Server) {
        for leader in self.core.online_leaders() {
            if let Some(player) = server.get_player_by_uuid(wit_uuid(leader)) {
                self.core.network().set_size_limit(leader, self.size_limit(&player));
            }
        }
        self.core.tick();
        self.deliver(server);
    }

    /// Applies what the core produced. No core lock is held here, so effects may re-enter the plugin (a teleport
    /// fires events; API subscribers may call back).
    fn deliver(&self, server: &Server) {
        loop {
            let effects = self.core.drain_effects();
            if effects.is_empty() {
                return;
            }
            for effect in effects {
                self.apply(server, effect);
            }
        }
    }

    fn apply(&self, server: &Server, effect: Effect) {
        let player = |id: Uuid| server.get_player_by_uuid(wit_uuid(id));
        match effect {
            Effect::Message { to, text } => {
                if let Some(p) = player(to) {
                    p.send_system_message(component(&text), false);
                }
            }
            Effect::SpyMessage { to, text } => {
                if let Some(p) = player(to) {
                    if p.has_permission(&self.spy) {
                        p.send_system_message(component(&text), false);
                    } else {
                        self.core.stop_spying(to); // revoked since they turned it on
                    }
                }
            }
            Effect::ActionBar { to, text } => {
                if let Some(p) = player(to) {
                    p.show_actionbar(component(&text));
                }
            }
            Effect::Sound { to, sound, volume, pitch } => {
                if let Some(p) = player(to) {
                    text::play_sound(&p, &sound, volume, pitch);
                }
            }
            Effect::Warp { members, leader, server: world, delay, skip_if_there } => {
                if delay.is_zero() {
                    warp(server, &members, leader, &world, skip_if_there);
                } else {
                    let ticks = delay.as_millis().div_ceil(50).max(1) as u64;
                    schedule_delayed_task(ticks, move |server| warp(&server, &members, leader, &world, skip_if_there));
                }
            }
            Effect::Left { player, party } => self.api.left(player, &party),
        }
    }

    pub(crate) fn deliver_anywhere(&self) {
        SERVER.with(|s| {
            if let Some(server) = s.borrow().as_ref() {
                self.deliver(server);
            }
        });
    }
}

/// Teleports the online `members` to `leader` (the proxy would connect them to the leader's server). With
/// `skip_if_there` (auto-warp), members already in `world` stay where they are.
fn warp(server: &Server, members: &[Uuid], leader: Uuid, world: &str, skip_if_there: bool) {
    let Some(leader) = server.get_player_by_uuid(wit_uuid(leader)) else { return };
    let position = leader.get_position();
    let (yaw, pitch) = (leader.get_yaw(), leader.get_pitch());
    for id in members {
        let Some(member) = server.get_player_by_uuid(wit_uuid(*id)) else { continue };
        if skip_if_there && world_of(&member) == world {
            continue;
        }
        member.teleport(position, Some(yaw), Some(pitch), leader.get_world());
    }
}

/// `/party` or `/pchat`: the rest of the line goes to the core as Velocity-style arguments. `word` is the
/// subcommand literal the player typed, if any.
struct Execute {
    chat: bool,
    word: Option<&'static str>,
}

impl CommandHandler for Execute {
    fn handle(&self, sender: CommandSender, server: Server, args: ConsumedArgs) -> Result<i32, CommandError> {
        let Some(rt) = runtime() else { return Ok(0) };
        let Some(player) = sender.as_player() else {
            sender.send_message(component(&rt.core.messages().simple("error.players-only")));
            return Ok(0);
        };
        let line = match args.get_value("args") {
            Arg::Simple(s) | Arg::Msg(s) => s,
            _ => String::new(),
        };
        let mut words = split_args(&line);
        if let Some(word) = self.word {
            words.insert(0, word.to_owned());
        }
        let sender = rt.sender(&player);
        if self.chat {
            rt.core.chat_command(&sender, &words);
        } else {
            rt.core.party_command(&sender, &words);
        }
        rt.deliver(&server);
        Ok(1)
    }
}

/// A command whose arguments the core parses: `words` become literal nodes (so Pumpkin completes them), each
/// followed by the rest of the line; anything else goes through the catch-all argument (`/p Steve`).
///
/// No server-side suggestions (`CommandNode::suggest`): Pumpkin's v0.1 Wasm host panics on the tick thread when a
/// player tab-completes such an argument, which stops the server (nightly of 2026-11-06, see the Friends port).
/// Player names are therefore not completed.
fn command(names: &[&str], description: &str, chat: bool, words: &[&'static str]) -> Command {
    let names: Vec<String> = names.iter().map(|n| (*n).to_owned()).collect();
    let rest = || ArgumentType::String(StringType::Greedy);
    let mut command = Command::new(&names, description);
    for word in words {
        command = command.then(
            CommandNode::literal(word)
                .then(CommandNode::argument("args", &rest()).execute(Execute { chat, word: Some(word) }))
                .execute(Execute { chat, word: Some(word) }),
        );
    }
    command
        .then(CommandNode::argument("args", &rest()).execute(Execute { chat, word: None }))
        .execute(Execute { chat, word: None })
}

struct Join;

impl EventHandler<PlayerJoinEvent> for Join {
    fn handle(&self, server: Server, event: PlayerJoinEventData) -> PlayerJoinEventData {
        if let Some(rt) = runtime() {
            rt.join(&server, &event.player);
        }
        event
    }
}

struct Leave;

impl EventHandler<PlayerLeaveEvent> for Leave {
    fn handle(&self, server: Server, event: PlayerLeaveEventData) -> PlayerLeaveEventData {
        if let Some(rt) = runtime() {
            rt.leave(&server, &event.player);
        }
        event
    }
}

/// The chat lock: locked players' lines go to party chat instead of the server.
struct Chat;

impl EventHandler<PlayerChatEvent> for Chat {
    fn handle(&self, server: Server, mut event: PlayerChatEventData) -> PlayerChatEventData {
        if event.cancelled {
            return event;
        }
        let Some(rt) = runtime() else { return event };
        if rt.core.chat(uuid(event.player.get_id()), &event.message) {
            event.cancelled = true;
            rt.deliver(&server);
        }
        event
    }
}

/// Auto-warp: a world change is this server's "server switch".
struct ChangedWorld;

impl EventHandler<PlayerChangedWorldEvent> for ChangedWorld {
    fn handle(&self, server: Server, event: PlayerChangedWorldEventData) -> PlayerChangedWorldEventData {
        if let Some(rt) = runtime() {
            rt.core.server_switched(uuid(event.player.get_id()), &event.to_world.get_id());
            rt.deliver(&server);
        }
        event
    }
}

/// `config.yml` / `messages.yml` from the data folder, written from the bundled files on first start.
fn read_or_create(folder: &str, name: &str, bundled: &str) -> Result<String, String> {
    let path = format!("{folder}/{name}");
    if !std::path::Path::new(&path).exists() {
        std::fs::create_dir_all(folder).map_err(|e| format!("can't create {folder}: {e}"))?;
        std::fs::write(&path, bundled).map_err(|e| format!("can't write {path}: {e}"))?;
    }
    std::fs::read_to_string(&path).map_err(|e| format!("can't read {path}: {e}"))
}

fn register(context: &Context, node: &str, description: &str, default: PermissionDefault) -> Result<(), String> {
    context.register_permission(&Permission {
        node: node.to_owned(),
        description: description.to_owned(),
        default,
        children: Vec::new(),
    })
}

struct PartiesPlugin;

impl PartiesPlugin {
    fn enable(context: &Context) -> Result<(), String> {
        let folder = context.get_data_folder();
        let config = PluginConfig::parse(&read_or_create(&folder, "config.yml", BUNDLED_CONFIG)?)?;
        let messages = Messages::load(&read_or_create(&folder, "messages.yml", BUNDLED_MESSAGES)?)?;
        if config.redis.enabled {
            tracing::warn!(
                "Parties & Chill: redis (parties shared between proxies) is for Velocity/BungeeCord; ignored on Pumpkin"
            );
        }

        register(context, COMMAND_PARTY, "Use /party", PermissionDefault::Allow)?;
        register(context, COMMAND_PCHAT, "Use /pchat", PermissionDefault::Allow)?;
        let spy = node(SPY_PERMISSION);
        register(context, &spy, "/pc spy: read every party's chat", PermissionDefault::Op(PermissionLevel::Three))?;
        for grant in &config.party.size_permissions {
            let size_node = node(&grant.permission);
            if size_node.starts_with(&format!("{NAME}:")) {
                let description = format!("Lead parties of up to {} members", grant.size);
                register(context, &size_node, &description, PermissionDefault::Deny)?;
            } else {
                tracing::warn!(
                    "Parties & Chill: {size_node} is another plugin's permission; it is checked, not registered"
                );
            }
        }

        let subscribers = Arc::new(api::Subscribers::default());
        let seed = Uuid::new_v4().as_u64_pair().0;
        let core =
            PartiesCore::new(config, messages, Arc::new(now), Box::new(api::IpcGuard(subscribers.clone())), seed);
        let timer = context.schedule_repeating_task(20, 20, |server| {
            if let Some(rt) = runtime() {
                rt.tick(&server);
            }
        });
        let rt = Arc::new(Runtime { core, api: subscribers, spy, sessions: Mutex::new(HashMap::new()), timer });
        *RUNTIME.write().unwrap_or_else(|e| e.into_inner()) = Some(rt.clone());
        SERVER.with(|s| *s.borrow_mut() = Some(context.get_server()));

        context.register_command(command(&["party", "p"], "Party commands", false, &PARTY_SUBCOMMANDS), COMMAND_PARTY);
        context.register_command(
            command(&["pchat", "pc", "party-chat"], "Party chat", true, &["toggle", "spy"]),
            COMMAND_PCHAT,
        );
        // Blocking: the chat lock must cancel before the line is broadcast, and joins/leaves keep their order.
        context.register_event_handler(Join, EventPriority::Normal, true)?;
        context.register_event_handler(Leave, EventPriority::Normal, true)?;
        context.register_event_handler(Chat, EventPriority::Highest, true)?;
        context.register_event_handler(ChangedWorld, EventPriority::Normal, true)?;

        // Loaded while players are online (reload): they join now.
        let server = context.get_server();
        for player in server.get_all_players() {
            rt.join(&server, &player);
        }
        let rules = &rt.core.config().party;
        tracing::info!(
            "Parties & Chill ready: parties of {} (up to {} by permission), invites {}, disconnect grace {}, auto-warp \
             worlds {:?}",
            rules.max_size,
            rules.size_permissions.first().map_or(rules.max_size, |g| g.size.max(rules.max_size)),
            parties_core::durations::format(rules.invite_ttl),
            parties_core::durations::format(rules.disconnect_grace),
            rules.game_servers
        );
        Ok(())
    }
}

impl Plugin for PartiesPlugin {
    fn new() -> Self {
        PartiesPlugin
    }

    fn metadata(&self) -> PluginMetadata {
        PluginMetadata {
            name: NAME.into(),
            version: env!("CARGO_PKG_VERSION").into(),
            authors: vec!["Parties & Chill".into()],
            description: "Hypixel-style parties: invites, roles, party chat, warps".into(),
            dependencies: vec![],
            // config.yml and messages.yml live in the data folder.
            permissions: vec![permissions::FS_WRITE_DATA.into()],
        }
    }

    fn on_load(&self, context: Context) -> pumpkin_plugin_api::Result<()> {
        PartiesPlugin::enable(&context).map_err(|e| {
            tracing::error!("Parties & Chill failed to start; party commands are disabled: {e}");
            if let Some(rt) = RUNTIME.write().unwrap_or_else(|e| e.into_inner()).take() {
                cancel_task(rt.timer);
            }
            e
        })
    }

    fn on_unload(&self, _context: Context) -> pumpkin_plugin_api::Result<()> {
        if let Some(rt) = RUNTIME.write().unwrap_or_else(|e| e.into_inner()).take() {
            cancel_task(rt.timer);
        }
        SERVER.with(|s| *s.borrow_mut() = None);
        Ok(())
    }

    fn handle_ipc_message(&self, sender: String, message: Vec<u8>) -> pumpkin_plugin_api::Result<Vec<u8>, String> {
        Ok(api::handle(&sender, &message))
    }
}

register_plugin!(PartiesPlugin);
