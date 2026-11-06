//! `chat.timestamp-format`: the hover timestamp of party chat lines, written with Java `DateTimeFormatter`
//! pattern letters. Plugins run in a WebAssembly sandbox without the host's time zone, so times are shown in UTC
//! (zone letters print `UTC` / `Z` / `+0000`).
//!
//! Supported: `y` `u` (year), `M` `L` (month, `MMM` short name, `MMMM` full name), `d`, `D` (day of year),
//! `E` (weekday, `EEEE` full name), `a` (AM/PM), `H` `k` `K` `h` (hours), `m`, `s`, `S` (fraction), `n`,
//! `'quoted text'`, `''`, the zone letters `z` `Z` `X` `x` `O` `V`, and any non-letter character.

use crate::party::Millis;

#[derive(Clone, Debug, PartialEq, Eq)]
enum Token {
    Literal(String),
    Field(char, usize),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TimestampFormat {
    tokens: Vec<Token>,
}

const MONTHS: [&str; 12] = [
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
];
const DAYS: [&str; 7] = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

impl TimestampFormat {
    /// # Errors
    /// For unknown pattern letters, reserved characters and unterminated quotes.
    pub fn new(pattern: &str) -> Result<TimestampFormat, String> {
        let chars: Vec<char> = pattern.chars().collect();
        let mut tokens = Vec::new();
        let mut i = 0;
        while i < chars.len() {
            let c = chars[i];
            if c == '\'' {
                if chars.get(i + 1) == Some(&'\'') {
                    tokens.push(Token::Literal("'".to_owned()));
                    i += 2;
                    continue;
                }
                let mut text = String::new();
                i += 1;
                loop {
                    match chars.get(i) {
                        None => return Err(format!("Pattern ends with an incomplete string literal: {pattern}")),
                        Some('\'') if chars.get(i + 1) == Some(&'\'') => {
                            text.push('\'');
                            i += 2;
                        }
                        Some('\'') => {
                            i += 1;
                            break;
                        }
                        Some(other) => {
                            text.push(*other);
                            i += 1;
                        }
                    }
                }
                tokens.push(Token::Literal(text));
            } else if c.is_ascii_alphabetic() {
                if !"yuMLdDEaHkKhmsSnzZXxOV".contains(c) {
                    return Err(format!("Unknown pattern letter: {c}"));
                }
                let start = i;
                while i < chars.len() && chars[i] == c {
                    i += 1;
                }
                tokens.push(Token::Field(c, i - start));
            } else if matches!(c, '[' | ']' | '{' | '}' | '#') {
                return Err(format!("Pattern includes reserved character: '{c}'"));
            } else {
                tokens.push(Token::Literal(c.to_string()));
                i += 1;
            }
        }
        Ok(TimestampFormat { tokens })
    }

    /// Formats `epoch_millis` in UTC.
    pub fn format(&self, epoch_millis: Millis) -> String {
        let days = epoch_millis.div_euclid(86_400_000);
        let in_day = epoch_millis.rem_euclid(86_400_000);
        let (year, month, day) = civil_from_days(days);
        let hour = in_day / 3_600_000;
        let minute = in_day / 60_000 % 60;
        let second = in_day / 1000 % 60;
        let millis = in_day % 1000;
        let weekday = (days + 3).rem_euclid(7) as usize; // 1970-01-01 was a Thursday (index 3 from Monday)
        let day_of_year = days - days_from_civil(year, 1, 1) + 1;
        let mut out = String::new();
        for token in &self.tokens {
            match token {
                Token::Literal(text) => out.push_str(text),
                Token::Field(letter, count) => {
                    let count = *count;
                    let number = |value: i64, out: &mut String| out.push_str(&format!("{value:0count$}"));
                    match letter {
                        'y' | 'u' => {
                            if count == 2 {
                                out.push_str(&format!("{:02}", year.rem_euclid(100)));
                            } else {
                                number(year, &mut out);
                            }
                        }
                        'M' | 'L' => match count {
                            1 | 2 => number(month, &mut out),
                            3 => out.push_str(&MONTHS[(month - 1) as usize][..3]),
                            4 => out.push_str(MONTHS[(month - 1) as usize]),
                            _ => out.push_str(&MONTHS[(month - 1) as usize][..1]),
                        },
                        'd' => number(day, &mut out),
                        'D' => number(day_of_year, &mut out),
                        'E' => match count {
                            1..=3 => out.push_str(&DAYS[weekday][..3]),
                            4 => out.push_str(DAYS[weekday]),
                            _ => out.push_str(&DAYS[weekday][..1]),
                        },
                        'a' => out.push_str(if hour < 12 { "AM" } else { "PM" }),
                        'H' => number(hour, &mut out),
                        'k' => number(if hour == 0 { 24 } else { hour }, &mut out),
                        'K' => number(hour % 12, &mut out),
                        'h' => number(if hour % 12 == 0 { 12 } else { hour % 12 }, &mut out),
                        'm' => number(minute, &mut out),
                        's' => number(second, &mut out),
                        'S' => {
                            let digits = format!("{:09}", millis * 1_000_000);
                            out.push_str(&digits[..count.min(9)]);
                            out.extend(std::iter::repeat_n('0', count.saturating_sub(9)));
                        }
                        'n' => number(millis * 1_000_000, &mut out),
                        'z' | 'V' => out.push_str("UTC"),
                        'O' => out.push_str("GMT"),
                        'X' => out.push('Z'),
                        'x' => out.push_str(match count {
                            1 => "+00",
                            2 | 4 => "+0000",
                            _ => "+00:00",
                        }),
                        _ => out.push_str(if count == 4 { "GMT" } else { "+0000" }), // 'Z'
                    }
                }
            }
        }
        out
    }
}

/// Days since 1970-01-01 → (year, month 1..=12, day 1..=31), proleptic Gregorian (Howard Hinnant's algorithm).
fn civil_from_days(days: i64) -> (i64, i64, i64) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    (year, month, day)
}

fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let year = if month <= 2 { year - 1 } else { year };
    let era = year.div_euclid(400);
    let yoe = year.rem_euclid(400);
    let mp = if month > 2 { month - 3 } else { month + 9 };
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}
