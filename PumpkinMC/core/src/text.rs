//! Styled chat text, independent of any server API: what the MiniMessage templates of `messages.yml` render to.
//!
//! A [`Text`] is a flat list of [`Span`]s, each carrying its complete style, so it maps one-to-one onto a parent
//! component with one child per span. The Pumpkin glue converts it to the server's text components.

/// Minecraft's 16 named chat colors.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum NamedColor {
    Black,
    DarkBlue,
    DarkGreen,
    DarkAqua,
    DarkRed,
    DarkPurple,
    Gold,
    Gray,
    DarkGray,
    Blue,
    Green,
    Aqua,
    Red,
    LightPurple,
    Yellow,
    White,
}

impl NamedColor {
    pub const ALL: [NamedColor; 16] = [
        NamedColor::Black,
        NamedColor::DarkBlue,
        NamedColor::DarkGreen,
        NamedColor::DarkAqua,
        NamedColor::DarkRed,
        NamedColor::DarkPurple,
        NamedColor::Gold,
        NamedColor::Gray,
        NamedColor::DarkGray,
        NamedColor::Blue,
        NamedColor::Green,
        NamedColor::Aqua,
        NamedColor::Red,
        NamedColor::LightPurple,
        NamedColor::Yellow,
        NamedColor::White,
    ];

    /// The MiniMessage / vanilla name, e.g. `dark_gray`.
    pub fn name(self) -> &'static str {
        match self {
            NamedColor::Black => "black",
            NamedColor::DarkBlue => "dark_blue",
            NamedColor::DarkGreen => "dark_green",
            NamedColor::DarkAqua => "dark_aqua",
            NamedColor::DarkRed => "dark_red",
            NamedColor::DarkPurple => "dark_purple",
            NamedColor::Gold => "gold",
            NamedColor::Gray => "gray",
            NamedColor::DarkGray => "dark_gray",
            NamedColor::Blue => "blue",
            NamedColor::Green => "green",
            NamedColor::Aqua => "aqua",
            NamedColor::Red => "red",
            NamedColor::LightPurple => "light_purple",
            NamedColor::Yellow => "yellow",
            NamedColor::White => "white",
        }
    }

    /// Looks a color up by name (case-insensitive); `grey` and `dark_grey` are accepted, as in MiniMessage.
    pub fn by_name(name: &str) -> Option<NamedColor> {
        let name = name.to_ascii_lowercase().replace("grey", "gray");
        NamedColor::ALL.into_iter().find(|c| c.name() == name)
    }

    /// The color's RGB value, used when it takes part in a gradient.
    pub fn rgb(self) -> Rgb {
        let value: u32 = match self {
            NamedColor::Black => 0x000000,
            NamedColor::DarkBlue => 0x0000AA,
            NamedColor::DarkGreen => 0x00AA00,
            NamedColor::DarkAqua => 0x00AAAA,
            NamedColor::DarkRed => 0xAA0000,
            NamedColor::DarkPurple => 0xAA00AA,
            NamedColor::Gold => 0xFFAA00,
            NamedColor::Gray => 0xAAAAAA,
            NamedColor::DarkGray => 0x555555,
            NamedColor::Blue => 0x5555FF,
            NamedColor::Green => 0x55FF55,
            NamedColor::Aqua => 0x55FFFF,
            NamedColor::Red => 0xFF5555,
            NamedColor::LightPurple => 0xFF55FF,
            NamedColor::Yellow => 0xFFFF55,
            NamedColor::White => 0xFFFFFF,
        };
        Rgb::from_u32(value)
    }
}

/// A 24-bit color.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Rgb {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

impl Rgb {
    pub const fn from_u32(value: u32) -> Rgb {
        Rgb { r: (value >> 16) as u8, g: (value >> 8) as u8, b: value as u8 }
    }

    /// Parses `#rrggbb`.
    pub fn parse_hex(text: &str) -> Option<Rgb> {
        let hex = text.strip_prefix('#')?;
        if hex.len() != 6 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
            return None;
        }
        u32::from_str_radix(hex, 16).ok().map(Rgb::from_u32)
    }

    /// Linear interpolation, rounded like Adventure's `TextColor.lerp` (`t` is clamped to 0..1).
    pub fn lerp(t: f32, a: Rgb, b: Rgb) -> Rgb {
        let t = t.clamp(0.0, 1.0);
        let channel = |x: u8, y: u8| (f32::from(x) + t * (f32::from(y) - f32::from(x))).round() as u8;
        Rgb { r: channel(a.r, b.r), g: channel(a.g, b.g), b: channel(a.b, b.b) }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Color {
    Named(NamedColor),
    Rgb(Rgb),
}

impl Color {
    pub fn rgb(self) -> Rgb {
        match self {
            Color::Named(named) => named.rgb(),
            Color::Rgb(rgb) => rgb,
        }
    }

    /// A named color (`red`, `dark_gray`...) or `#rrggbb`.
    pub fn parse(text: &str) -> Option<Color> {
        if text.starts_with('#') {
            Rgb::parse_hex(text).map(Color::Rgb)
        } else {
            NamedColor::by_name(text).map(Color::Named)
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Click {
    RunCommand(String),
    SuggestCommand(String),
    OpenUrl(String),
    CopyToClipboard(String),
    ChangePage(u32),
}

/// Everything a span can be dressed in. `None` means "inherit" (from the surrounding text, else the default).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Style {
    pub color: Option<Color>,
    pub bold: Option<bool>,
    pub italic: Option<bool>,
    pub underlined: Option<bool>,
    pub strikethrough: Option<bool>,
    pub obfuscated: Option<bool>,
    pub click: Option<Click>,
    pub hover: Option<Box<Text>>,
    pub insertion: Option<String>,
    pub font: Option<String>,
}

impl Style {
    /// Fills every unset field from `parent`, as a child component inherits its parent's style.
    pub fn inherit(&mut self, parent: &Style) {
        fn fill<T: Clone>(field: &mut Option<T>, parent: &Option<T>) {
            if field.is_none() {
                field.clone_from(parent);
            }
        }
        fill(&mut self.color, &parent.color);
        fill(&mut self.bold, &parent.bold);
        fill(&mut self.italic, &parent.italic);
        fill(&mut self.underlined, &parent.underlined);
        fill(&mut self.strikethrough, &parent.strikethrough);
        fill(&mut self.obfuscated, &parent.obfuscated);
        fill(&mut self.click, &parent.click);
        fill(&mut self.hover, &parent.hover);
        fill(&mut self.insertion, &parent.insertion);
        fill(&mut self.font, &parent.font);
    }
}

/// A run of text in one style.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Span {
    pub text: String,
    pub style: Style,
}

/// Styled text: spans in reading order.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Text {
    pub spans: Vec<Span>,
}

impl Text {
    /// Unstyled text (player names, chat lines, unknown message keys).
    pub fn plain(text: impl Into<String>) -> Text {
        let mut out = Text::default();
        out.push(text.into(), Style::default());
        out
    }

    /// Appends a span, merging it into the previous one when the styles match.
    pub fn push(&mut self, text: String, style: Style) {
        if text.is_empty() {
            return;
        }
        if let Some(last) = self.spans.last_mut()
            && last.style == style
        {
            last.text.push_str(&text);
            return;
        }
        self.spans.push(Span { text, style });
    }

    /// Appends every span of `other`.
    pub fn append(&mut self, other: Text) {
        for span in other.spans {
            self.push(span.text, span.style);
        }
    }

    /// The text without its styles, e.g. for the console and for tests.
    pub fn plain_text(&self) -> String {
        self.spans.iter().map(|s| s.text.as_str()).collect()
    }

    pub fn is_empty(&self) -> bool {
        self.spans.iter().all(|s| s.text.is_empty())
    }

    /// The spans whose text contains `needle`, for tests and lookups.
    pub fn find(&self, needle: &str) -> Option<&Span> {
        self.spans.iter().find(|s| s.text.contains(needle))
    }
}
