//! `Durations`: parses and prints the short durations used in `config.yml` (`500ms`, `60s`, `5m`, `1h30m`).

use std::time::Duration;

/// Parses `text`, e.g. `"1m30s"`; a bare number is read as seconds. Parts may be separated by spaces, and a unit may
/// follow its number after spaces (`"1 m 30 s"`), exactly like the Java pattern `(\d+)\s*(ms|s|m|h|d)`.
///
/// # Errors
/// `invalid duration: '<text>'` for anything else, including values too large to represent.
pub fn parse(text: &str) -> Result<Duration, String> {
    let value = text.trim().to_ascii_lowercase();
    let invalid = || format!("invalid duration: '{text}'");
    if !value.is_empty() && value.bytes().all(|b| b.is_ascii_digit()) {
        return value.parse::<u64>().map(Duration::from_secs).map_err(|_| invalid());
    }
    let bytes = value.as_bytes();
    let mut pos = 0;
    let mut total = Duration::ZERO;
    while pos < bytes.len() {
        let start = pos;
        while pos < bytes.len() && bytes[pos].is_ascii_digit() {
            pos += 1;
        }
        if pos == start {
            return Err(invalid());
        }
        let amount: u64 = value[start..pos].parse().map_err(|_| invalid())?;
        while pos < bytes.len() && bytes[pos].is_ascii_whitespace() {
            pos += 1;
        }
        let rest = &value[pos..];
        // "ms" before "m", as in the Java alternation (ms|s|m|h|d).
        let (unit_millis, length) = if rest.starts_with("ms") {
            (1, 2)
        } else {
            match rest.bytes().next() {
                Some(b's') => (1_000, 1),
                Some(b'm') => (60_000, 1),
                Some(b'h') => (3_600_000, 1),
                Some(b'd') => (86_400_000, 1),
                _ => return Err(invalid()),
            }
        };
        pos += length;
        let millis = amount.checked_mul(unit_millis).ok_or_else(invalid)?;
        total = total.checked_add(Duration::from_millis(millis)).ok_or_else(invalid)?;
        while pos < bytes.len() && bytes[pos] == b' ' {
            pos += 1;
        }
    }
    if pos == 0 {
        return Err(invalid());
    }
    Ok(total)
}

/// A compact form such as `5m`, `1m30s` or `45s`, rounded down to seconds (`0s` below one second).
pub fn format(duration: Duration) -> String {
    let seconds = duration.as_secs();
    if seconds == 0 {
        return "0s".to_owned();
    }
    let (days, hours, minutes, secs) = (seconds / 86_400, seconds % 86_400 / 3600, seconds % 3600 / 60, seconds % 60);
    let mut out = String::new();
    for (amount, unit) in [(days, 'd'), (hours, 'h'), (minutes, 'm'), (secs, 's')] {
        if amount > 0 {
            out.push_str(&amount.to_string());
            out.push(unit);
        }
    }
    out
}

/// Milliseconds of `duration`, saturating (config durations are far below the limit).
pub fn millis(duration: Duration) -> i64 {
    i64::try_from(duration.as_millis()).unwrap_or(i64::MAX)
}
