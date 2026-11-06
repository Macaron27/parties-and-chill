//! `PluginConfig`: the parsed `config.yml`, the same file and keys as the Velocity and BungeeCord plugins.

use std::collections::HashSet;
use std::time::Duration;

use yaml_rust2::Yaml;

use crate::durations;
use crate::party::PartySettings;
use crate::rules::{PartyRules, Right, SizePermission};
use crate::timestamp::TimestampFormat;
use crate::yaml::{self, node};

/// The bundled `config.yml`, the same file the Velocity and BungeeCord plugins ship.
pub const BUNDLED_CONFIG: &str = include_str!("../../../core/src/main/resources/config.yml");

/// How party chat lines are dressed up.
#[derive(Clone, Debug)]
pub struct ChatConfig {
    /// Formats the hover timestamp of party chat lines (UTC on Pumpkin).
    pub timestamps: TimestampFormat,
    /// Sound played to `@mentioned` players; empty for none.
    pub mention_sound: String,
    pub volume: f32,
    pub pitch: f32,
}

impl ChatConfig {
    /// What the bundled `config.yml` says.
    pub fn defaults() -> ChatConfig {
        ChatConfig {
            timestamps: TimestampFormat::new("HH:mm:ss").unwrap_or_else(|e| unreachable!("{e}")),
            mention_sound: "ENTITY_EXPERIENCE_ORB_PICKUP".to_owned(),
            volume: 1.0,
            pitch: 1.0,
        }
    }
}

/// Multi-proxy synchronisation settings. Read for completeness: a Pumpkin server runs single-server parties.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RedisConfig {
    pub enabled: bool,
    pub uri: String,
    pub prefix: String,
    pub proxy_id: String,
}

#[derive(Clone, Debug)]
pub struct PluginConfig {
    pub party: PartyRules,
    pub chat: ChatConfig,
    pub redis: RedisConfig,
}

/// `roles.moderator` keys.
const MODERATOR_KEYS: [(&str, Right); 7] = [
    ("invite", Right::Invite),
    ("kick", Right::Kick),
    ("warp", Right::Warp),
    ("promote", Right::Promote),
    ("settings", Right::Settings),
    ("start-games", Right::StartGames),
    ("chat-moderation", Right::ModerateChat),
];

impl PluginConfig {
    /// Parses `config.yml`; missing keys take the bundled defaults.
    ///
    /// # Errors
    /// A message naming the offending key, e.g. `config.yml: party.max-size: party size must be 2..1024, got 1`.
    pub fn parse(text: &str) -> Result<PluginConfig, String> {
        let root = yaml::parse(text).map_err(|e| format!("config.yml: {e}"))?;
        let built_in = PartyRules::defaults();
        let auto_warp = yaml::bool_or(node(&root, &["auto-warp", "enabled"]), true);
        let max_size = yaml::int_or(node(&root, &["party", "max-size"]), i64::from(built_in.max_size));
        let party = PartyRules::new(
            size(max_size).map_err(|e| format!("config.yml: party.max-size: {e}"))?,
            duration(&root, "60s", &["party", "invite-timeout"])?,
            duration(&root, "5m", &["party", "disconnect-grace"])?,
            duration(&root, "1s", &["auto-warp", "delay"])?,
            if auto_warp { strings(node(&root, &["auto-warp", "servers"]), "auto-warp.servers")? } else { Vec::new() },
            duration(&root, "30s", &["chat", "muted-notice-cooldown"])?,
            size_permissions(node(&root, &["party", "permission-sizes"]))?,
            moderator_rights(node(&root, &["roles", "moderator"]), &built_in.moderator_rights),
            defaults(node(&root, &["party", "defaults"])),
        )
        .map_err(|e| format!("config.yml: party.{e}"))?;

        let redis = node(&root, &["redis"]);
        let proxy_id = yaml::string_or(&redis["proxy-id"], "");
        let proxy_id = if proxy_id.trim().is_empty() {
            format!("proxy-{}", &uuid::Uuid::new_v4().to_string()[..8])
        } else {
            proxy_id
        };
        Ok(PluginConfig {
            party,
            chat: chat(node(&root, &["chat"]))?,
            redis: RedisConfig {
                enabled: yaml::bool_or(&redis["enabled"], false),
                uri: yaml::string_or(&redis["uri"], "redis://localhost:6379/0"),
                prefix: yaml::string_or(&redis["prefix"], "pnc:"),
                proxy_id,
            },
        })
    }
}

/// `permission: size` pairs; YAML keys keep their dots (`parties.size.16` is one key).
fn size_permissions(node: &Yaml) -> Result<Vec<SizePermission>, String> {
    let Yaml::Hash(map) = node else { return Ok(Vec::new()) };
    let mut sizes = Vec::new();
    for (permission, size) in map {
        let permission = yaml::key(permission);
        let key = format!("config.yml: party.permission-sizes.{permission}");
        let Yaml::Integer(value) = size else { return Err(format!("{key} must be a whole number")) };
        let value = self::size(*value).map_err(|e| format!("{key} — {permission}: {e}"))?;
        sizes.push(SizePermission::new(permission, value).map_err(|e| format!("{key} — {e}"))?);
    }
    Ok(sizes)
}

/// A size that fits the rules' type; the range itself is checked by [`PartyRules`] / [`SizePermission`].
fn size(value: i64) -> Result<u32, String> {
    u32::try_from(value).map_err(|_| format!("party size must be 2..{}, got {value}", crate::rules::MAX_PARTY_SIZE))
}

fn moderator_rights(node: &Yaml, fallback: &HashSet<Right>) -> HashSet<Right> {
    MODERATOR_KEYS
        .iter()
        .filter(|(key, right)| yaml::bool_or(&node[*key], fallback.contains(right)))
        .map(|(_, right)| *right)
        .collect()
}

fn defaults(node: &Yaml) -> PartySettings {
    let built_in = PartySettings::DEFAULTS;
    PartySettings {
        auto_warp: yaml::bool_or(&node["auto-warp"], built_in.auto_warp),
        chat: yaml::bool_or(&node["chat"], built_in.chat),
        all_invite: yaml::bool_or(&node["all-invite"], built_in.all_invite),
        all_warp: yaml::bool_or(&node["all-warp"], built_in.all_warp),
        open: yaml::bool_or(&node["public"], built_in.open),
        ..built_in // max size, mute and slow mode always start off
    }
}

fn chat(node: &Yaml) -> Result<ChatConfig, String> {
    let built_in = ChatConfig::defaults();
    let pattern = yaml::string_or(&node["timestamp-format"], "HH:mm:ss");
    let timestamps = TimestampFormat::new(&pattern).map_err(|e| format!("config.yml: chat.timestamp-format — {e}"))?;
    let mention = &node["mention"];
    Ok(ChatConfig {
        timestamps,
        mention_sound: yaml::string_or(&mention["sound"], &built_in.mention_sound).trim().to_owned(),
        volume: yaml::float_or(&mention["volume"], built_in.volume),
        pitch: yaml::float_or(&mention["pitch"], built_in.pitch),
    })
}

fn duration(root: &Yaml, fallback: &str, path: &[&str]) -> Result<Duration, String> {
    let raw = yaml::string_or(node(root, path), fallback);
    durations::parse(&raw).map_err(|e| format!("config.yml: {} — {e}", path.join(".")))
}

fn strings(node: &Yaml, path: &str) -> Result<Vec<String>, String> {
    match node {
        Yaml::Array(items) => items
            .iter()
            .map(|item| yaml::string(item).ok_or_else(|| format!("config.yml: {path} must be a list of server names")))
            .collect(),
        Yaml::BadValue | Yaml::Null => Ok(Vec::new()),
        _ => Err(format!("config.yml: {path} must be a list of server names")),
    }
}
