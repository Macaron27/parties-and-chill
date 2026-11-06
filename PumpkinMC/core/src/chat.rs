//! `PartyChat`: one-off messages (`/pc <message>`), the chat lock (`/pc toggle`, or `/pc` alone), which diverts
//! everything a player types into party chat, and staff spying on every party's chat (`/pc spy`).

use std::time::Duration;

use uuid::Uuid;

use crate::manager::{Outcome, lock};
use crate::party::Millis;
use crate::{PartiesCore, durations};

/// Vanilla chat limit; longer lines are cut.
pub const MAX_LENGTH: usize = 256;
/// Bounds the name lookups one line can cause.
const MAX_MENTIONS: usize = 8;

/// An `@name` tag in a chat line: byte offsets of the whole tag and the name (without `@`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Mention {
    pub start: usize,
    pub end: usize,
    pub name: String,
}

/// `@name` tags, as the Java pattern `(?<![\w@])@([.*]?\w{1,16})`: a Java Edition name (Floodgate's `.` / `*`
/// prefix allowed), not preceded by a word character or `@`, so e-mail addresses don't count.
pub fn mentions(text: &str) -> Vec<Mention> {
    let word = |c: char| c.is_ascii_alphanumeric() || c == '_';
    let mut out = Vec::new();
    let mut previous: Option<char> = None;
    for (start, c) in text.char_indices() {
        let preceded = previous.is_some_and(|p| word(p) || p == '@');
        previous = Some(c);
        if c != '@' || preceded {
            continue;
        }
        let mut end = start + 1;
        let mut name = String::new();
        if let Some(prefix @ ('.' | '*')) = text[end..].chars().next() {
            name.push(prefix);
            end += 1;
        }
        let mut length = 0;
        for c in text[end..].chars().take_while(|c| word(*c)).take(16) {
            name.push(c);
            end += c.len_utf8();
            length += 1;
        }
        if length > 0 {
            out.push(Mention { start, end, name });
        }
    }
    out
}

impl PartiesCore {
    /// `true` if everything `player` types goes to party chat.
    pub fn is_locked(&self, player: Uuid) -> bool {
        lock(&self.chat).locked.contains(&player)
    }

    /// Flips the chat lock (a Pumpkin server cancels chat itself, so it is always available).
    pub fn toggle_lock(&self, player: Uuid) {
        if lock(&self.chat).locked.remove(&player) {
            self.reply(player, self.messages.simple("chat.toggled-off"));
            return;
        }
        if self.manager.party_of(player).is_none() {
            self.reply(player, self.messages.simple(Outcome::NotInParty.message_key()));
            return;
        }
        lock(&self.chat).locked.insert(player);
        self.reply(player, self.messages.simple("chat.toggled-on"));
    }

    /// Players reading every party's chat.
    pub fn spies(&self) -> Vec<Uuid> {
        lock(&self.chat).spies.iter().copied().collect()
    }

    /// Flips spy mode; callers check [`crate::SPY_PERMISSION`].
    pub fn toggle_spy(&self, player: Uuid) {
        let on = {
            let mut chat = lock(&self.chat);
            let on = chat.spies.insert(player);
            if !on {
                chat.spies.remove(&player);
            }
            on
        };
        self.reply(player, self.messages.simple(if on { "spy.enabled" } else { "spy.disabled" }));
    }

    /// Ends spy mode silently (the permission was revoked since it was turned on).
    pub fn stop_spying(&self, player: Uuid) {
        lock(&self.chat).spies.remove(&player);
    }

    /// Drops the lock silently (the player left the party).
    pub fn unlock(&self, player: Uuid) -> bool {
        lock(&self.chat).locked.remove(&player)
    }

    /// Forgets a player who disconnected.
    pub fn forget(&self, player: Uuid) {
        let mut chat = lock(&self.chat);
        chat.locked.remove(&player);
        chat.spies.remove(&player);
    }

    /// Sends a party chat line, honouring mutes and the party's chat settings. `muted_until_hint`: a mute reported
    /// along with the line (`0` if none).
    pub fn send_chat(&self, sender: Uuid, message: &str, muted_until_hint: Millis) {
        let text: String = message.chars().take(MAX_LENGTH).collect();
        let muted_until = muted_until_hint.max(self.network.muted_until(sender));
        let outcome = self.manager.chat(sender, &text, muted_until, || self.resolve_mentions(&text));
        match outcome {
            Outcome::Muted => {
                let reply = if muted_until == Millis::MAX {
                    self.messages.simple("error.muted-permanent")
                } else {
                    let left = Duration::from_millis(u64::try_from(muted_until - self.now()).unwrap_or(0));
                    self.messages.render_values("error.muted", &[("time", durations::format(left))])
                };
                self.reply(sender, reply);
            }
            Outcome::NotInParty | Outcome::ChatDisabled => {
                // A lock would swallow every line they type: give their chat back.
                self.unlock(sender);
                self.reply(sender, self.messages.simple(outcome.message_key()));
            }
            _ if !outcome.message_key().is_empty() => self.reply(sender, self.messages.simple(outcome.message_key())),
            _ => {}
        }
        self.dispatch();
    }

    /// Players `text` tags with `@name` (the manager keeps only party members).
    fn resolve_mentions(&self, text: &str) -> Vec<Uuid> {
        if !text.contains('@') {
            return Vec::new(); // the common case: no lookups at all
        }
        let mut names: Vec<String> = Vec::new();
        for mention in mentions(text) {
            let name = mention.name.to_lowercase();
            if names.len() == MAX_MENTIONS {
                break;
            }
            if !names.contains(&name) {
                names.push(name);
            }
        }
        names.iter().filter_map(|name| self.network.uuid_of(name)).collect()
    }
}
