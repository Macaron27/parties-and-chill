//! Small helpers over `yaml-rust2`, standing in for Configurate's node getters (value or default).

use yaml_rust2::{Yaml, YamlLoader};

/// Parses a YAML document; an empty file is an empty map.
///
/// # Errors
/// The scanner's message for invalid YAML.
pub fn parse(text: &str) -> Result<Yaml, String> {
    let mut documents = YamlLoader::load_from_str(text).map_err(|e| e.to_string())?;
    Ok(if documents.is_empty() { Yaml::Hash(Default::default()) } else { documents.swap_remove(0) })
}

/// The node at `path` (map keys), or `Yaml::BadValue` when missing.
pub fn node<'a>(root: &'a Yaml, path: &[&str]) -> &'a Yaml {
    let mut node = root;
    for key in path {
        node = &node[*key];
    }
    node
}

/// A scalar as text (numbers and booleans too, as Configurate's `getString` does); `None` for maps, lists, null.
pub fn string(node: &Yaml) -> Option<String> {
    match node {
        Yaml::String(s) | Yaml::Real(s) => Some(s.clone()),
        Yaml::Integer(i) => Some(i.to_string()),
        Yaml::Boolean(b) => Some(b.to_string()),
        _ => None,
    }
}

pub fn string_or(node: &Yaml, default: &str) -> String {
    string(node).unwrap_or_else(|| default.to_owned())
}

/// A boolean (`true`/`false`, also as text, and `yes`/`no`/`on`/`off`); `default` when missing or not boolean.
pub fn bool_or(node: &Yaml, default: bool) -> bool {
    match node {
        Yaml::Boolean(b) => *b,
        Yaml::String(s) => match s.to_ascii_lowercase().as_str() {
            "true" | "yes" | "on" => true,
            "false" | "no" | "off" => false,
            _ => default,
        },
        _ => default,
    }
}

/// A whole number (also as text); `default` when missing or not a number.
pub fn int_or(node: &Yaml, default: i64) -> i64 {
    match node {
        Yaml::Integer(i) => *i,
        Yaml::String(s) => s.trim().parse().unwrap_or(default),
        _ => default,
    }
}

/// A number (also as text); `default` when missing or not a number.
pub fn float_or(node: &Yaml, default: f32) -> f32 {
    match node {
        Yaml::Integer(i) => *i as f32,
        Yaml::Real(s) | Yaml::String(s) => s.trim().parse().unwrap_or(default),
        _ => default,
    }
}

/// The key of a map entry as text (`parties.size.16` stays one key).
pub fn key(node: &Yaml) -> String {
    string(node).unwrap_or_default()
}
