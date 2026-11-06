//! `config.yml`, `messages.yml`, the MiniMessage renderer and timestamps (Java's `PluginConfigTest` and
//! `MessagesTest`, plus the renderer the Java plugin gets from Adventure).

use std::collections::{BTreeSet, HashSet};
use std::time::Duration;

use parties_core::config::{BUNDLED_CONFIG, PluginConfig};
use parties_core::messages::Messages;
use parties_core::minimessage::{Placeholders, render};
use parties_core::party::{PartySettings, Toggle};
use parties_core::rules::{PartyRules, Right, SizePermission};
use parties_core::text::{Click, Color, NamedColor, Rgb, Span, Text};
use parties_core::timestamp::TimestampFormat;

// --- config.yml ---------------------------------------------------------------------------------------

#[test]
fn the_bundled_config_is_the_default() {
    let config = PluginConfig::parse(BUNDLED_CONFIG).unwrap();
    let defaults = PartyRules::defaults();
    assert_eq!(defaults.max_size, config.party.max_size);
    assert_eq!(defaults.invite_ttl, config.party.invite_ttl);
    assert_eq!(defaults.disconnect_grace, config.party.disconnect_grace);
    assert_eq!(defaults.warp_delay, config.party.warp_delay);
    assert_eq!(defaults.game_servers, config.party.game_servers);
    assert_eq!(defaults.size_permissions, config.party.size_permissions);
    assert_eq!(defaults.moderator_rights, config.party.moderator_rights);
    assert_eq!(defaults.party_defaults, config.party.party_defaults);
    assert_eq!("ENTITY_EXPERIENCE_ORB_PICKUP", config.chat.mention_sound);
    assert_eq!((1.0, 1.0), (config.chat.volume, config.chat.pitch));
    assert_eq!(8, config.party.size_limit(|_| false));
    assert_eq!(24, config.party.size_limit(|p| ["parties.size.12", "parties.size.24"].contains(&p)));
    assert!(!config.redis.enabled);
    assert_eq!("redis://localhost:6379/0", config.redis.uri);
    assert_eq!("pnc:", config.redis.prefix);
    assert!(config.redis.proxy_id.starts_with("proxy-"), "blank proxy-id gets a random one");
    assert_eq!(config.party.max_size, PluginConfig::parse("").unwrap().party.max_size, "an empty file is all defaults");
}

#[test]
fn reads_edited_values() {
    let config = PluginConfig::parse(
        r#"
party:
  max-size: 12
  invite-timeout: 2m
  disconnect-grace: 90s
auto-warp:
  enabled: false
  delay: 500ms
  servers: ["bw-*"]
redis:
  enabled: true
  proxy-id: "eu-1"
"#,
    )
    .unwrap();
    assert_eq!(12, config.party.max_size);
    assert_eq!(Duration::from_secs(120), config.party.invite_ttl);
    assert_eq!(Duration::from_secs(90), config.party.disconnect_grace);
    assert_eq!(Duration::from_millis(500), config.party.warp_delay);
    assert!(config.party.game_servers.is_empty(), "auto-warp disabled = no game servers");
    assert_eq!(Duration::from_secs(30), config.party.muted_notice_cooldown, "missing keys use defaults");
    assert!(config.redis.enabled);
    assert_eq!("eu-1", config.redis.proxy_id);
}

#[test]
fn reads_roles_sizes_defaults_and_chat() {
    let config = PluginConfig::parse(
        r#"
party:
  max-size: 5
  permission-sizes:
    ranks.gold: 10
    ranks.diamond: 20
  defaults:
    public: true
    all-warp: true
roles:
  moderator:
    kick: false
    settings: true
chat:
  timestamp-format: "dd/MM HH:mm"
  mention:
    sound: "minecraft:block.note_block.pling"
    volume: 0.5
    pitch: 2
"#,
    )
    .unwrap();
    assert_eq!(
        vec![SizePermission::new("ranks.diamond", 20).unwrap(), SizePermission::new("ranks.gold", 10).unwrap()],
        config.party.size_permissions,
        "dotted keys stay whole, biggest first"
    );
    let expected: HashSet<Right> =
        [Right::Invite, Right::Warp, Right::Settings, Right::StartGames, Right::ModerateChat].into_iter().collect();
    assert_eq!(expected, config.party.moderator_rights, "listed keys override, missing ones keep their default");
    assert_eq!(
        PartySettings::DEFAULTS.with(Toggle::Public, true).with(Toggle::AllWarp, true),
        config.party.party_defaults
    );
    assert_eq!("minecraft:block.note_block.pling", config.chat.mention_sound);
    assert_eq!((0.5, 2.0), (config.chat.volume, config.chat.pitch));
    assert_eq!("01/01 00:00", config.chat.timestamps.format(0));
}

#[test]
fn invalid_values_name_the_key() {
    let error = PluginConfig::parse("party:\n  invite-timeout: soon\n").unwrap_err();
    assert_eq!("config.yml: party.invite-timeout — invalid duration: 'soon'", error);
    for (yaml, expected) in [
        ("party:\n  permission-sizes:\n    parties.size.huge: 5000\n", "party.permission-sizes.parties.size."),
        ("party:\n  permission-sizes:\n    parties.size.many: lots\n", "party.permission-sizes.parties.size."),
        ("party:\n  permission-sizes:\n    parties.size.neg: -3\n", "party.permission-sizes.parties.size."),
        ("party:\n  max-size: 1\n", "party.max-size"),
        ("party:\n  max-size: -1\n", "party.max-size"),
        ("chat:\n  timestamp-format: \"HH:mm{\"\n", "chat.timestamp-format"),
        ("auto-warp:\n  servers: bw-1\n", "auto-warp.servers"),
        ("party: [\n", ""),
    ] {
        let error = PluginConfig::parse(yaml).unwrap_err();
        assert!(error.starts_with(&format!("config.yml: {expected}")), "{yaml:?} gave {error}");
    }
    assert_eq!(
        "config.yml: party.permission-sizes.parties.size.huge — parties.size.huge: party size must be 2..1024, got 5000",
        PluginConfig::parse("party:\n  permission-sizes:\n    parties.size.huge: 5000\n").unwrap_err()
    );
}

// --- timestamps ---------------------------------------------------------------------------------------

#[test]
fn timestamps_follow_java_pattern_letters_in_utc() {
    let at = 1_767_225_600_000 + ((13 * 60 + 4) * 60 + 5) * 1000 + 678; // 2026-01-01 13:04:05.678 UTC, a Thursday
    let format = |pattern: &str| TimestampFormat::new(pattern).unwrap().format(at);
    assert_eq!("13:04:05", format("HH:mm:ss"));
    assert_eq!("2026-01-01 1:04 PM", format("yyyy-MM-dd h:mm a"));
    assert_eq!("Thu, 1 Jan 26", format("EEE, d MMM yy"));
    assert_eq!("Thursday January 001", format("EEEE MMMM DDD"));
    assert_eq!("05.678 UTC", format("ss.SSS z"));
    assert_eq!("at 13 o'clock", format("'at' H 'o''clock'"));
    assert_eq!("00:00:00", TimestampFormat::new("HH:mm:ss").unwrap().format(0));
    assert_eq!("1969-12-31", TimestampFormat::new("yyyy-MM-dd").unwrap().format(-1));
    assert!(TimestampFormat::new("HH:mm{").is_err());
    assert!(TimestampFormat::new("bb").is_err());
    assert!(TimestampFormat::new("'open").is_err());
}

// --- messages.yml -------------------------------------------------------------------------------------

fn players() -> Vec<(&'static str, String)> {
    [("player", "Alice"), ("target", "Bob"), ("leader", "Carol"), ("owner", "Dave"), ("key", "allinvite")]
        .map(|(k, v)| (k, v.to_owned()))
        .to_vec()
}

fn values() -> Vec<(&'static str, String)> {
    [
        ("time", "60s"),
        ("server", "bw-1"),
        ("count", "3"),
        ("message", "hi"),
        ("max", "8"),
        ("usage", "/p invite <player>"),
        ("setting", "public"),
        ("limit", "16"),
    ]
    .map(|(k, v)| (k, v.to_owned()))
    .to_vec()
}

fn components() -> Vec<(&'static str, Text)> {
    vec![("status", Text::plain("*")), ("name", Text::plain("N")), ("state", Text::plain("ON"))]
}

/// Every click value of `action` in `text` (hover texts included).
fn clicks(text: &Text, run: bool) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    for span in &text.spans {
        match &span.style.click {
            Some(Click::RunCommand(c)) if run => {
                out.insert(c.clone());
            }
            Some(Click::SuggestCommand(c)) if !run => {
                out.insert(c.clone());
            }
            _ => {}
        }
    }
    out
}

fn hovers(text: &Text) -> BTreeSet<String> {
    text.spans.iter().filter_map(|s| s.style.hover.as_ref()).map(|h| h.plain_text()).collect()
}

#[test]
fn every_bundled_message_renders_without_leftover_tags() {
    let messages = Messages::defaults();
    let keys: Vec<String> = messages.keys().map(str::to_owned).collect();
    assert!(keys.len() > 80, "flattened keys: {}", keys.len());
    for key in keys {
        let plain = messages.render(&key, &players(), &values(), &components()).plain_text();
        let without_usage = plain.replace("/p invite <player>", "");
        let leftover = without_usage.find('<').filter(|i| {
            without_usage[i + 1..].chars().next().is_some_and(|c| c.is_ascii_lowercase() || "#/!".contains(c))
        });
        assert!(leftover.is_none(), "{key} has an unknown tag: {plain}");
    }
}

#[test]
fn every_key_used_in_code_exists() {
    let messages = Messages::defaults();
    let keys: HashSet<&str> = messages.keys().collect();
    let sources = [
        include_str!("../src/manager.rs"),
        include_str!("../src/chat.rs"),
        include_str!("../src/command.rs"),
        include_str!("../src/lib.rs"),
    ];
    let prefixes = [
        "error.",
        "invite.",
        "party.",
        "member.",
        "leader.",
        "role.",
        "disband.",
        "warp.",
        "chat.",
        "spy.",
        "settings.",
        "list.",
        "status.",
    ];
    let mut missing = BTreeSet::new();
    for source in sources {
        for literal in source.split('"').skip(1).step_by(2) {
            let is_key = literal == "help"
                || prefixes.iter().any(|p| literal.starts_with(p))
                    && literal.chars().all(|c| c.is_ascii_lowercase() || c == '.' || c == '-')
                    && !literal.ends_with('.');
            if is_key && !keys.contains(literal) {
                missing.insert(literal.to_owned());
            }
        }
    }
    for toggle in Toggle::ALL {
        let key = format!("settings.name.{}", toggle.key());
        if !keys.contains(key.as_str()) {
            missing.insert(key);
        }
    }
    assert!(missing.is_empty(), "keys referenced in code but absent from messages.yml: {missing:?}");
}

#[test]
fn chat_lines_have_a_clickable_name_and_a_hover_timestamp() {
    let messages = Messages::defaults();
    let line = messages.render(
        "chat.format",
        &[("player", "Macaron27".to_owned())],
        &[("time", "12:34:56".to_owned())],
        &[("message", Text::plain("@Steve we're ready!"))],
    );
    assert_eq!("[Party] Macaron27 » @Steve we're ready!", line.plain_text());
    assert_eq!(BTreeSet::from(["/msg Macaron27 ".to_owned()]), clicks(&line, false));
    assert!(hovers(&line).contains("Sent at 12:34:56"), "hovers: {:?}", hovers(&line));
    let name = line.find("Macaron27").unwrap();
    assert_eq!(Some(Color::Named(NamedColor::White)), name.style.color);
    let message = line.find("ready").unwrap();
    assert_eq!(Some(Color::Rgb(Rgb::from_u32(0xe5e7eb))), message.style.color, "the message inherits <#e5e7eb>");
}

#[test]
fn settings_menu_lines_run_their_command() {
    let messages = Messages::defaults();
    let toggle = messages.render(
        "settings.toggle",
        &[("key", "allinvite".to_owned())],
        &[],
        &[("name", messages.simple("settings.name.allinvite")), ("state", messages.simple("settings.state-on"))],
    );
    assert_eq!("Members can invite » ON", toggle.plain_text());
    assert_eq!(BTreeSet::from(["/party settings allinvite".to_owned()]), clicks(&toggle, true));
    let size = messages.render_values("settings.max-size-line", &[("max", "8".to_owned()), ("limit", "16".to_owned())]);
    assert_eq!(BTreeSet::from(["/party settings maxsize ".to_owned()]), clicks(&size, false));
    assert!(hovers(&size).contains("Click to change. Your limit: 16"));
}

#[test]
fn chat_text_is_never_parsed() {
    let messages = Messages::defaults();
    let evil = "<red>boom<click:run_command:'/op me'>x</click>";
    let chat = messages.render(
        "chat.format",
        &[("player", "<bold>Mallory".to_owned())],
        &[],
        &[("message", Text::plain(evil))],
    );
    let plain = chat.plain_text();
    assert!(plain.contains(evil), "{plain}");
    assert!(plain.contains("<bold>Mallory"), "names with tag characters are escaped");
    assert!(clicks(&chat, true).is_empty());
    assert!(chat.spans.iter().all(|s| s.style.bold != Some(true) || !s.text.contains("Mallory")));
}

#[test]
fn invite_buttons_run_commands_with_the_inviter_name() {
    let messages = Messages::defaults();
    let invite =
        messages.render("invite.received", &[("player", "Alice_01".to_owned())], &[("time", "60s".to_owned())], &[]);
    assert_eq!(
        BTreeSet::from(["/party accept Alice_01".to_owned(), "/party deny Alice_01".to_owned()]),
        clicks(&invite, true)
    );
    assert!(hovers(&invite).contains("Join the party of Alice_01"));
    let accept = invite.find("[ACCEPT]").unwrap();
    assert_eq!((Some(true), Some(Color::Rgb(Rgb::from_u32(0x34d399)))), (accept.style.bold, accept.style.color));
}

#[test]
fn user_file_overrides_and_falls_back_to_defaults() {
    let custom = Messages::load("prefix: \"[P] \"\nerror:\n  not-leader: \"<prefix>leaders only\"\n").unwrap();
    assert_eq!("[P] leaders only", custom.simple("error.not-leader").plain_text());
    assert!(custom.simple("error.party-full").plain_text().starts_with("[P] "), "default template + custom prefix");
    assert_eq!("no.such.key", custom.simple("no.such.key").plain_text(), "unknown keys show themselves");
    assert!(Messages::load("help: [\n").err().is_some_and(|e| e.starts_with("messages.yml: ")));
}

#[test]
fn list_messages_become_lines() {
    let help = Messages::defaults().simple("help").plain_text();
    assert_eq!(16, help.lines().count());
    assert!(help.lines().nth(1).unwrap().contains("Party Commands"));
}

// --- MiniMessage --------------------------------------------------------------------------------------

fn mm(template: &str) -> Text {
    render(template, &Placeholders::default())
}

#[test]
fn colors_decorations_and_closing_tags() {
    let text = mm("<red>a<bold>b</bold>c</red>d<#00ff00>e</#00ff00><dark_grey>f<!bold>g<b:false>h");
    let style = |needle: &str| text.find(needle).unwrap().style.clone();
    assert_eq!(Some(Color::Named(NamedColor::Red)), style("a").color);
    assert_eq!((Some(Color::Named(NamedColor::Red)), Some(true)), (style("b").color, style("b").bold));
    assert_eq!(None, style("c").bold);
    assert_eq!(None, style("d").color);
    assert_eq!(Some(Color::Rgb(Rgb::from_u32(0x00ff00))), style("e").color);
    assert_eq!(Some(Color::Named(NamedColor::DarkGray)), style("f").color);
    assert_eq!(Some(false), style("g").bold);
    assert_eq!("abcdefgh", text.plain_text());
}

#[test]
fn unknown_tags_escapes_and_stray_brackets_stay_text() {
    assert_eq!("<foo>x</foo> 3 < 4 > 2 <b", mm("<foo>x</foo> 3 < 4 > 2 <b").plain_text());
    assert_eq!("<red>not red \\", mm("\\<red>not red \\\\").plain_text());
    assert_eq!(None, mm("\\<red>x").spans[0].style.color);
    assert_eq!("line1\nline2", mm("line1<newline>line2").plain_text());
    assert_eq!(None, mm("<red><bold>a<reset>b").find("b").unwrap().style.color);
}

#[test]
fn gradients_color_every_character() {
    let text = mm("<gradient:#000000:#ffffff>abc</gradient>");
    let colors: Vec<Option<Color>> = text.spans.iter().map(|s| s.style.color).collect();
    assert_eq!(
        vec![
            Some(Color::Rgb(Rgb::from_u32(0x000000))),
            Some(Color::Rgb(Rgb::from_u32(0x808080))),
            Some(Color::Rgb(Rgb::from_u32(0xffffff)))
        ],
        colors
    );
    let separator = Messages::defaults().simple("separator");
    assert_eq!(60, separator.spans.len(), "60 struck-through spaces, each its own color");
    assert!(separator.spans.iter().all(|s| s.style.strikethrough == Some(true)));
    assert_eq!(Some(Color::Rgb(Rgb::from_u32(0xa78bfa))), separator.spans[0].style.color);
    assert_eq!(Some(Color::Rgb(Rgb::from_u32(0x38bdf8))), separator.spans[59].style.color);
    let inner = mm("<gradient:red:blue>a<white>b</white>c</gradient>");
    assert_eq!(Some(Color::Named(NamedColor::White)), inner.find("b").unwrap().style.color, "inner colors win");
    assert_eq!(Some(Color::Rgb(NamedColor::Blue.rgb())), inner.find("c").unwrap().style.color);
    assert_eq!("<gradient:red>x", mm("<gradient:red>x").plain_text(), "one color is not a gradient");
    assert_eq!(3, mm("<rainbow>abc").spans.len());
}

#[test]
fn clicks_hovers_and_placeholders() {
    let placeholders = Placeholders {
        parsed: vec![("player".to_owned(), "Bob".to_owned())],
        unparsed: vec![("time".to_owned(), "<12:00>".to_owned())],
        components: vec![(
            "icon".to_owned(),
            Text { spans: vec![Span { text: "*".to_owned(), style: Default::default() }] },
        )],
    };
    let text = render(
        "<click:run_command:'/party accept <player>'><hover:show_text:'<green>at <time>'><red><icon> <player></red></hover></click> <click:open_url:https://example.org/a:b>link</click>",
        &placeholders,
    );
    assert_eq!("* Bob link", text.plain_text());
    let star = text.find("*").unwrap();
    assert_eq!(Some(Click::RunCommand("/party accept Bob".to_owned())), star.style.click);
    assert_eq!(Some(Color::Named(NamedColor::Red)), star.style.color, "components inherit the surrounding style");
    assert_eq!("at <12:00>", star.style.hover.as_ref().unwrap().plain_text());
    assert_eq!(Some(Click::OpenUrl("https://example.org/a:b".to_owned())), text.find("link").unwrap().style.click);
}
