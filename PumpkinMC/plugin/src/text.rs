//! Core text to Pumpkin text components, UUID conversions and mention sounds.

use std::collections::HashSet;
use std::sync::Mutex;

use parties_core::text::{Click, Color, NamedColor, Span, Text};
use pumpkin_plugin_api::Player;
use pumpkin_plugin_api::common::{self, RgbColor};
use pumpkin_plugin_api::text::TextComponent;
use pumpkin_plugin_api::wit::pumpkin::plugin::sounds::SoundCategory;

fn named(color: NamedColor) -> common::NamedColor {
    match color {
        NamedColor::Black => common::NamedColor::Black,
        NamedColor::DarkBlue => common::NamedColor::DarkBlue,
        NamedColor::DarkGreen => common::NamedColor::DarkGreen,
        NamedColor::DarkAqua => common::NamedColor::DarkAqua,
        NamedColor::DarkRed => common::NamedColor::DarkRed,
        NamedColor::DarkPurple => common::NamedColor::DarkPurple,
        NamedColor::Gold => common::NamedColor::Gold,
        NamedColor::Gray => common::NamedColor::Gray,
        NamedColor::DarkGray => common::NamedColor::DarkGray,
        NamedColor::Blue => common::NamedColor::Blue,
        NamedColor::Green => common::NamedColor::Green,
        NamedColor::Aqua => common::NamedColor::Aqua,
        NamedColor::Red => common::NamedColor::Red,
        NamedColor::LightPurple => common::NamedColor::LightPurple,
        NamedColor::Yellow => common::NamedColor::Yellow,
        NamedColor::White => common::NamedColor::White,
    }
}

fn span(span: &Span) -> TextComponent {
    let style = &span.style;
    let mut c = TextComponent::text(&span.text);
    c = match style.color {
        Some(Color::Named(color)) => c.color_named(named(color)),
        Some(Color::Rgb(rgb)) => c.color_rgb(RgbColor { r: rgb.r, g: rgb.g, b: rgb.b }),
        None => c,
    };
    if let Some(value) = style.bold {
        c = c.bold(value);
    }
    if let Some(value) = style.italic {
        c = c.italic(value);
    }
    if let Some(value) = style.underlined {
        c = c.underlined(value);
    }
    if let Some(value) = style.strikethrough {
        c = c.strikethrough(value);
    }
    if let Some(value) = style.obfuscated {
        c = c.obfuscated(value);
    }
    c = match &style.click {
        Some(Click::RunCommand(command)) => c.click_run_command(command),
        Some(Click::SuggestCommand(command)) => c.click_suggest_command(command),
        Some(Click::OpenUrl(url)) => c.click_open_url(url),
        Some(Click::CopyToClipboard(text)) => c.click_copy_to_clipboard(text),
        Some(Click::ChangePage(page)) => c.click_change_page(*page),
        None => c,
    };
    if let Some(hover) = &style.hover {
        c = c.hover_show_text(component(hover));
    }
    if let Some(insertion) = &style.insertion {
        c = c.insertion(insertion);
    }
    if let Some(font) = &style.font {
        c = c.font(font);
    }
    c
}

/// One child per span under an unstyled root, so no style leaks from one span to the next.
pub fn component(text: &Text) -> TextComponent {
    if let [only] = text.spans.as_slice() {
        return span(only);
    }
    let mut root = TextComponent::text("");
    for child in &text.spans {
        root = root.add_child(span(child));
    }
    root
}

pub fn uuid(id: pumpkin_plugin_api::uuid::Uuid) -> uuid::Uuid {
    uuid::Uuid::from_u64_pair(id.high, id.low)
}

pub fn wit_uuid(id: uuid::Uuid) -> pumpkin_plugin_api::uuid::Uuid {
    let (high, low) = id.as_u64_pair();
    pumpkin_plugin_api::uuid::Uuid { high, low }
}

/// Names already reported as unknown (logged once, like the Paper bridge).
static UNKNOWN: Mutex<Option<HashSet<String>>> = Mutex::new(None);

/// Plays `chat.mention.sound` to `player`: a namespaced key (`minecraft:block.note_block.pling`) as is, or one of
/// the common Bukkit names. Unknown names are logged once and skipped.
pub fn play_sound(player: &Player, name: &str, volume: f32, pitch: f32) {
    if let Some(key) = parties_core::sound::key(name) {
        player.play_custom_sound(&key, SoundCategory::Master, volume, pitch);
        return;
    }
    let mut unknown = UNKNOWN.lock().unwrap_or_else(|e| e.into_inner());
    if unknown.get_or_insert_with(HashSet::new).insert(name.to_owned()) {
        tracing::warn!(
            "Parties & Chill: unknown sound '{name}' (chat.mention.sound in config.yml); use a namespaced key such as \
             minecraft:entity.experience_orb.pickup"
        );
    }
}
