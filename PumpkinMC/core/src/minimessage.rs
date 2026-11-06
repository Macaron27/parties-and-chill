//! A [MiniMessage](https://docs.advntr.dev/minimessage/format.html) renderer for the templates of `messages.yml`,
//! producing [`Text`]. The Java plugin renders the same files with Adventure's MiniMessage; this covers the tags
//! that make sense in chat:
//!
//! - colors: `<red>`, `<dark_gray>` (and `grey`), `<#a78bfa>`, `<color:...>` / `<colour:...>` / `<c:...>`;
//! - decorations: `<bold>` `<b>`, `<italic>` `<i>` `<em>`, `<underlined>` `<u>`, `<strikethrough>` `<st>`,
//!   `<obfuscated>` `<obf>`, negated with `<!bold>` or `<bold:false>`;
//! - `<gradient[:color...][:phase]>`, `<rainbow[:!][phase]>`;
//! - `<click:run_command|suggest_command|open_url|copy_to_clipboard|change_page:value>`, `<hover:show_text:'...'>`,
//!   `<insert:text>`, `<font:key>`;
//! - `<newline>` / `<br>`, `<reset>`, closing tags, `\<` escapes, quoted arguments.
//!
//! Like MiniMessage, unknown tags are printed as they are, so typos show up in-game.

use crate::text::{Click, Color, NamedColor, Rgb, Style, Text};

/// Values for a template's own tags (`<player>`, `<time>`...).
#[derive(Clone, Debug, Default)]
pub struct Placeholders {
    /// Substituted into the template before it is parsed, so they also work inside tag arguments (Adventure's
    /// `Placeholder.parsed`). Only for values that can't contain tags.
    pub parsed: Vec<(String, String)>,
    /// Inserted as literal text, never parsed (Adventure's `Placeholder.unparsed`).
    pub unparsed: Vec<(String, String)>,
    /// Inserted as they are (Adventure's `Placeholder.component`).
    pub components: Vec<(String, Text)>,
}

impl Placeholders {
    fn unparsed(&self, name: &str) -> Option<&str> {
        self.unparsed.iter().find(|(n, _)| n == name).map(|(_, v)| v.as_str())
    }

    fn component(&self, name: &str) -> Option<&Text> {
        self.components.iter().find(|(n, _)| n == name).map(|(_, v)| v)
    }

    fn is_placeholder(&self, name: &str) -> bool {
        self.parsed.iter().any(|(n, _)| n == name) || self.unparsed(name).is_some() || self.component(name).is_some()
    }
}

/// Renders `template` with `placeholders`.
pub fn render(template: &str, placeholders: &Placeholders) -> Text {
    let mut source = template.to_owned();
    for (name, value) in &placeholders.parsed {
        source = source.replace(&format!("<{name}>"), value);
    }
    parse(&source, placeholders)
}

fn parse(source: &str, placeholders: &Placeholders) -> Text {
    let mut builder = Builder { placeholders, stack: Vec::new(), shades: Vec::new(), raw: Vec::new() };
    let mut text = String::new();
    let mut i = 0;
    while let Some(c) = source[i..].chars().next() {
        if c == '\\' {
            match source[i + 1..].chars().next() {
                Some(next @ ('<' | '\\')) => {
                    text.push(next);
                    i += 2;
                }
                _ => {
                    text.push('\\');
                    i += 1;
                }
            }
            continue;
        }
        if c == '<'
            && let Some((tag, end)) = Tag::parse(source, i)
        {
            builder.text(std::mem::take(&mut text));
            if !builder.tag(&tag) {
                builder.text(source[i..end].to_owned());
            }
            i = end;
            continue;
        }
        text.push(c);
        i += c.len_utf8();
    }
    builder.text(text);
    builder.finish()
}

/// `<name:arg:...>`, `</name>` or `<!name>`.
struct Tag {
    closing: bool,
    negated: bool,
    name: String,
    args: Vec<String>,
}

impl Tag {
    /// Reads the tag starting at the `<` at `start`; `None` if it isn't one (the `<` is then plain text).
    fn parse(source: &str, start: usize) -> Option<(Tag, usize)> {
        let mut parts = vec![String::new()];
        let mut quote: Option<char> = None;
        let mut chars = source[start + 1..].char_indices().peekable();
        while let Some((offset, c)) = chars.next() {
            let part = parts.last_mut()?;
            if let Some(q) = quote {
                if c == '\\'
                    && let Some(&(_, next)) = chars.peek()
                    && (next == q || next == '\\')
                {
                    part.push(next);
                    chars.next();
                } else if c == q {
                    quote = None;
                } else {
                    part.push(c);
                }
                continue;
            }
            match c {
                '>' => {
                    let end = start + 1 + offset + 1;
                    return Tag::from_parts(parts).map(|tag| (tag, end));
                }
                '<' | '\n' => return None,
                ':' => parts.push(String::new()),
                '\'' | '"' if part.is_empty() => quote = Some(c),
                _ => part.push(c),
            }
        }
        None
    }

    fn from_parts(mut parts: Vec<String>) -> Option<Tag> {
        let head = parts.remove(0);
        let (closing, negated, name) = if let Some(name) = head.strip_prefix('/') {
            (true, false, name)
        } else if let Some(name) = head.strip_prefix('!') {
            (false, true, name)
        } else {
            (false, false, head.as_str())
        };
        let valid = |c: char| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '#' | '.');
        if name.is_empty() || !name.chars().all(valid) {
            return None;
        }
        Some(Tag { closing, negated, name: name.to_ascii_lowercase(), args: parts })
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Decoration {
    Bold,
    Italic,
    Underlined,
    Strikethrough,
    Obfuscated,
}

impl Decoration {
    fn by_name(name: &str) -> Option<Decoration> {
        Some(match name {
            "bold" | "b" => Decoration::Bold,
            "italic" | "i" | "em" => Decoration::Italic,
            "underlined" | "u" => Decoration::Underlined,
            "strikethrough" | "st" => Decoration::Strikethrough,
            "obfuscated" | "obf" => Decoration::Obfuscated,
            _ => return None,
        })
    }

    fn name(self) -> &'static str {
        match self {
            Decoration::Bold => "bold",
            Decoration::Italic => "italic",
            Decoration::Underlined => "underlined",
            Decoration::Strikethrough => "strikethrough",
            Decoration::Obfuscated => "obfuscated",
        }
    }
}

/// What an open tag changes for the text after it.
enum Change {
    Color(Color),
    Decoration(Decoration, bool),
    Click(Click),
    Hover(Box<Text>),
    Insertion(String),
    Font(String),
    /// A gradient or rainbow: colors are assigned per character once its length is known.
    Shade(usize),
}

struct Frame {
    /// Canonical tag name, matched by closing tags.
    name: String,
    change: Change,
}

enum ShadeKind {
    Gradient { colors: Vec<Rgb>, phase: f32 },
    Rainbow { reversed: bool, phase: f32 },
}

struct Shade {
    kind: ShadeKind,
    /// Characters inside the tag (all of them, including those an inner color overrides).
    count: usize,
}

impl Shade {
    /// Adventure's algorithm: colors evenly spread over the characters, shifted by the phase.
    fn color(&self, index: usize) -> Rgb {
        let size = self.count.max(1);
        match &self.kind {
            ShadeKind::Gradient { colors, phase } => {
                let mut colors = colors.clone();
                let mut phase = *phase;
                if phase < 0.0 {
                    colors.reverse();
                    phase += 1.0;
                }
                let n = colors.len();
                let multiplier = if size == 1 { 0.0 } else { (n - 1) as f32 / (size - 1) as f32 };
                let position = index as f32 * multiplier + phase * (n - 1) as f32;
                let low_unclamped = position.floor();
                let low = (low_unclamped as usize) % n;
                let high = (position.ceil() as usize) % n;
                Rgb::lerp(position - low_unclamped, colors[low], colors[high])
            }
            ShadeKind::Rainbow { reversed, phase } => {
                let mut hue = (index as f32 / size as f32 + phase / 10.0).rem_euclid(1.0);
                if *reversed {
                    hue = 1.0 - hue;
                }
                hsv(hue)
            }
        }
    }
}

/// A fully saturated, full-brightness color of `hue` (0..1).
fn hsv(hue: f32) -> Rgb {
    let h = hue * 6.0;
    let x = 1.0 - (h.rem_euclid(2.0) - 1.0).abs();
    let (r, g, b) = match h as u32 {
        0 => (1.0, x, 0.0),
        1 => (x, 1.0, 0.0),
        2 => (0.0, 1.0, x),
        3 => (0.0, x, 1.0),
        4 => (x, 0.0, 1.0),
        _ => (1.0, 0.0, x),
    };
    let channel = |v: f32| (v * 255.0).round() as u8;
    Rgb { r: channel(r), g: channel(g), b: channel(b) }
}

enum Source {
    /// The span's style has its final color (or none).
    Own,
    /// Character `index` of shade `id`.
    Shade { id: usize, index: usize },
}

struct Raw {
    text: String,
    style: Style,
    source: Source,
}

struct Builder<'p> {
    placeholders: &'p Placeholders,
    stack: Vec<Frame>,
    shades: Vec<Shade>,
    raw: Vec<Raw>,
}

impl Builder<'_> {
    /// The style the open tags produce, and the shade coloring the text if the innermost color comes from one.
    fn current(&self) -> (Style, Option<usize>) {
        let mut style = Style::default();
        let mut shade = None;
        for frame in &self.stack {
            match &frame.change {
                Change::Color(color) => {
                    style.color = Some(*color);
                    shade = None;
                }
                Change::Shade(id) => {
                    style.color = None;
                    shade = Some(*id);
                }
                Change::Decoration(decoration, value) => {
                    let field = match decoration {
                        Decoration::Bold => &mut style.bold,
                        Decoration::Italic => &mut style.italic,
                        Decoration::Underlined => &mut style.underlined,
                        Decoration::Strikethrough => &mut style.strikethrough,
                        Decoration::Obfuscated => &mut style.obfuscated,
                    };
                    *field = Some(*value);
                }
                Change::Click(click) => style.click = Some(click.clone()),
                Change::Hover(hover) => style.hover = Some(hover.clone()),
                Change::Insertion(text) => style.insertion = Some(text.clone()),
                Change::Font(font) => style.font = Some(font.clone()),
            }
        }
        (style, shade)
    }

    fn text(&mut self, text: String) {
        self.styled(text, Style::default());
    }

    /// Appends `text`; fields set in `own` (a component placeholder's style) win over the open tags.
    fn styled(&mut self, text: String, own: Style) {
        if text.is_empty() {
            return;
        }
        let (current, shade) = self.current();
        let shaded = if own.color.is_none() { shade } else { None };
        let mut style = own;
        style.inherit(&current);
        let active: Vec<usize> =
            self.stack.iter().filter_map(|f| if let Change::Shade(id) = f.change { Some(id) } else { None }).collect();
        if active.is_empty() {
            self.raw.push(Raw { text, style, source: Source::Own });
            return;
        }
        for c in text.chars() {
            let source = match shaded {
                Some(id) => Source::Shade { id, index: self.shades[id].count },
                None => Source::Own,
            };
            for &id in &active {
                self.shades[id].count += 1;
            }
            self.raw.push(Raw { text: c.to_string(), style: style.clone(), source });
        }
    }

    fn push(&mut self, name: impl Into<String>, change: Change) -> bool {
        self.stack.push(Frame { name: name.into(), change });
        true
    }

    /// Applies a tag; `false` if it isn't one this renderer knows (it is then printed as typed).
    fn tag(&mut self, tag: &Tag) -> bool {
        let name = tag.name.as_str();
        if tag.closing {
            let canonical = canonical(name);
            if let Some(pos) = self.stack.iter().rposition(|f| f.name == canonical) {
                self.stack.truncate(pos);
                return true;
            }
            return self.placeholders.is_placeholder(name) || is_known(&canonical);
        }
        if let Some(value) = self.placeholders.unparsed(name) {
            self.text(value.to_owned());
            return true;
        }
        if let Some(component) = self.placeholders.component(name) {
            for span in component.spans.clone() {
                self.styled(span.text, span.style);
            }
            return true;
        }
        let arg = |i: usize| tag.args.get(i).map(String::as_str);
        if let Some(decoration) = Decoration::by_name(name) {
            let value = !tag.negated && arg(0) != Some("false");
            return self.push(decoration.name(), Change::Decoration(decoration, value));
        }
        match name {
            "newline" | "br" => {
                self.text("\n".to_owned());
                true
            }
            "reset" => {
                self.stack.clear();
                true
            }
            "color" | "colour" | "c" => match arg(0).and_then(Color::parse) {
                Some(color) => self.push("color", Change::Color(color)),
                None => false,
            },
            "click" => {
                let value = tag.args.get(1..).map(|rest| rest.join(":")).unwrap_or_default();
                let click = match arg(0) {
                    Some("run_command") => Click::RunCommand(value),
                    Some("suggest_command") => Click::SuggestCommand(value),
                    Some("open_url") => Click::OpenUrl(value),
                    Some("copy_to_clipboard") => Click::CopyToClipboard(value),
                    Some("change_page") => match value.trim().parse() {
                        Ok(page) => Click::ChangePage(page),
                        Err(_) => return false,
                    },
                    _ => return false,
                };
                self.push("click", Change::Click(click))
            }
            "hover" => match (arg(0), tag.args.get(1..)) {
                (Some("show_text"), Some(rest)) if !rest.is_empty() => {
                    let hover = parse(&rest.join(":"), self.placeholders);
                    self.push("hover", Change::Hover(Box::new(hover)))
                }
                _ => false,
            },
            "insert" | "insertion" => match arg(0) {
                Some(_) => self.push("insert", Change::Insertion(tag.args.join(":"))),
                None => false,
            },
            "font" => match arg(0) {
                Some(_) => self.push("font", Change::Font(tag.args.join(":"))),
                None => false,
            },
            "gradient" => match gradient(&tag.args) {
                Some(kind) => self.shade("gradient", kind),
                None => false,
            },
            "rainbow" => {
                let spec = arg(0).unwrap_or("");
                let (reversed, phase) = match spec.strip_prefix('!') {
                    Some(rest) => (true, rest),
                    None => (false, spec),
                };
                let phase = if phase.is_empty() { Some(0.0) } else { phase.parse::<i32>().ok().map(|p| p as f32) };
                match phase {
                    Some(phase) => self.shade("rainbow", ShadeKind::Rainbow { reversed, phase }),
                    None => false,
                }
            }
            _ => match Color::parse(name) {
                Some(color) => self.push(canonical(name), Change::Color(color)),
                None => false,
            },
        }
    }

    fn shade(&mut self, name: &str, kind: ShadeKind) -> bool {
        self.shades.push(Shade { kind, count: 0 });
        let id = self.shades.len() - 1;
        self.push(name, Change::Shade(id))
    }

    fn finish(self) -> Text {
        let mut out = Text::default();
        for raw in self.raw {
            let mut style = raw.style;
            if let Source::Shade { id, index } = raw.source {
                style.color = Some(Color::Rgb(self.shades[id].color(index)));
            }
            out.push(raw.text, style);
        }
        out
    }
}

/// `<gradient:#a:#b[:phase]>`; white to black without colors; `None` for invalid arguments (fewer than two colors).
fn gradient(args: &[String]) -> Option<ShadeKind> {
    if args.is_empty() {
        return Some(ShadeKind::Gradient {
            colors: vec![Rgb::from_u32(0xFFFFFF), Rgb::from_u32(0x000000)],
            phase: 0.0,
        });
    }
    let mut colors = Vec::new();
    let mut phase = 0.0;
    for (i, arg) in args.iter().enumerate() {
        if let Some(color) = Color::parse(arg) {
            colors.push(color.rgb());
        } else if i == args.len() - 1
            && let Ok(value) = arg.parse::<f32>()
            && (-1.0..=1.0).contains(&value)
        {
            phase = value;
        } else {
            return None;
        }
    }
    (colors.len() >= 2).then_some(ShadeKind::Gradient { colors, phase })
}

/// The name an open frame is stored under, so `</b>` closes `<bold>` and `</grey>` closes `<gray>`.
fn canonical(name: &str) -> String {
    if let Some(decoration) = Decoration::by_name(name) {
        return decoration.name().to_owned();
    }
    match name {
        "colour" | "c" => "color".to_owned(),
        "insertion" => "insert".to_owned(),
        _ => match NamedColor::by_name(name) {
            Some(color) => color.name().to_owned(),
            None => name.to_owned(),
        },
    }
}

fn is_known(canonical: &str) -> bool {
    matches!(canonical, "color" | "click" | "hover" | "insert" | "font" | "gradient" | "rainbow" | "reset")
        || Decoration::by_name(canonical).is_some()
        || Color::parse(canonical).is_some()
}
