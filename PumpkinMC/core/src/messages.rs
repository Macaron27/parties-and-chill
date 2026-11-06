//! `Messages`: `messages.yml` rendered with MiniMessage. Keys are dotted paths (`invite.received`); YAML lists become
//! multi-line messages. Keys missing from the user's file fall back to the bundled defaults.

use std::collections::HashMap;

use yaml_rust2::Yaml;

use crate::minimessage::{self, Placeholders};
use crate::text::Text;
use crate::yaml;

/// The bundled `messages.yml`, the same file the Velocity and BungeeCord plugins ship.
pub const BUNDLED_MESSAGES: &str = include_str!("../../../core/src/main/resources/messages.yml");

pub struct Messages {
    templates: HashMap<String, String>,
    /// `<prefix>` and `<separator>`, rendered once: their styles can't leak into the text after them.
    globals: Vec<(String, Text)>,
}

/// Characters a Minecraft/Floodgate username can contain; such names are safe to parse (needed inside click args).
fn safe_name(name: &str) -> bool {
    (1..=36).contains(&name.chars().count())
        && name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '*' | '-'))
}

impl Messages {
    /// The bundled messages overlaid with `user` (the server's `messages.yml`).
    ///
    /// # Errors
    /// `messages.yml: <reason>` if `user` is not valid YAML.
    pub fn load(user: &str) -> Result<Messages, String> {
        let mut templates = bundled_templates();
        let root = yaml::parse(user).map_err(|e| format!("messages.yml: {e}"))?;
        flatten("", &root, &mut templates);
        Ok(Messages::from_templates(templates))
    }

    /// Messages built from the bundled `messages.yml` only.
    pub fn defaults() -> Messages {
        Messages::from_templates(bundled_templates())
    }

    fn from_templates(templates: HashMap<String, String>) -> Messages {
        let global = |key: &str| {
            let template = templates.get(key).map_or("", String::as_str);
            (key.to_owned(), minimessage::render(template, &Placeholders::default()))
        };
        let globals = vec![global("prefix"), global("separator")];
        Messages { templates, globals }
    }

    /// Every known key.
    pub fn keys(&self) -> impl Iterator<Item = &str> {
        self.templates.keys().map(String::as_str)
    }

    /// The raw template of `key`.
    pub fn template(&self, key: &str) -> Option<&str> {
        self.templates.get(key).map(String::as_str)
    }

    /// Renders `key`. `players`: placeholder → player name; `values`: placeholder → raw text (never parsed);
    /// `components`: placeholder → pre-rendered text. An unknown key renders as the key itself so typos are visible.
    pub fn render(
        &self,
        key: &str,
        players: &[(&str, String)],
        values: &[(&str, String)],
        components: &[(&str, Text)],
    ) -> Text {
        let Some(template) = self.templates.get(key) else { return Text::plain(key) };
        let mut placeholders = Placeholders::default();
        for (tag, name) in players {
            // Names are parsed only when they can't contain tags: parsed placeholders also work in click/hover args.
            let entry = ((*tag).to_owned(), name.clone());
            if safe_name(name) {
                placeholders.parsed.push(entry);
            } else {
                placeholders.unparsed.push(entry);
            }
        }
        placeholders.unparsed.extend(values.iter().map(|(tag, value)| ((*tag).to_owned(), value.clone())));
        placeholders.components.extend(self.globals.iter().cloned());
        placeholders.components.extend(components.iter().map(|(tag, text)| ((*tag).to_owned(), text.clone())));
        minimessage::render(template, &placeholders)
    }

    /// Renders a message whose placeholders are all raw text.
    pub fn render_values(&self, key: &str, values: &[(&str, String)]) -> Text {
        self.render(key, &[], values, &[])
    }

    /// Renders a message without placeholders.
    pub fn simple(&self, key: &str) -> Text {
        self.render(key, &[], &[], &[])
    }
}

fn bundled_templates() -> HashMap<String, String> {
    let mut templates = HashMap::new();
    let root = yaml::parse(BUNDLED_MESSAGES).unwrap_or_else(|e| panic!("bundled messages.yml is invalid: {e}"));
    flatten("", &root, &mut templates);
    templates
}

fn flatten(prefix: &str, node: &Yaml, out: &mut HashMap<String, String>) {
    match node {
        Yaml::Hash(map) => {
            for (key, child) in map {
                let key = yaml::key(key);
                let path = if prefix.is_empty() { key } else { format!("{prefix}.{key}") };
                flatten(&path, child, out);
            }
        }
        Yaml::Array(items) => {
            let lines: Vec<String> = items.iter().map(|item| yaml::string(item).unwrap_or_default()).collect();
            out.insert(prefix.to_owned(), lines.join("<newline>"));
        }
        other => {
            if let Some(text) = yaml::string(other) {
                out.insert(prefix.to_owned(), text);
            }
        }
    }
}
