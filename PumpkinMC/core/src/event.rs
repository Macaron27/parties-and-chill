//! `PartyEvent`: side effects produced by [`crate::manager::PartyManager`], as plain data. The dispatcher in
//! [`crate::PartiesCore`] turns them into chat lines, warps and API events for the players on this server.

use std::sync::Arc;
use std::time::Duration;

use uuid::Uuid;

use crate::party::{Millis, Party};

#[derive(Clone, Debug, PartialEq)]
pub enum PartyEvent {
    /// A chat message rendered from `messages.yml`.
    Notice {
        /// Who receives it (no duplicates).
        recipients: Vec<Uuid>,
        /// Message key, e.g. `invite.received`.
        key: &'static str,
        /// Placeholder name → player (rendered as the player's name).
        players: Vec<(&'static str, Uuid)>,
        /// Placeholder name → raw text (never parsed as MiniMessage).
        values: Vec<(&'static str, String)>,
    },
    /// A party chat line, rendered for the recipients, staff spying on party chat, and mentioned players.
    Chat {
        /// Party members who receive it.
        recipients: Vec<Uuid>,
        sender: Uuid,
        /// The party owner (spies see whose party it is).
        owner: Uuid,
        /// The raw text, never parsed as MiniMessage.
        message: String,
        /// Members tagged with `@name`: they also get an action bar and a ping.
        mentioned: Vec<Uuid>,
        /// Shown as the hover timestamp.
        sent_at: Millis,
    },
    /// Moves members to `leader`'s server (on Pumpkin: teleports them to the leader).
    Warp {
        members: Vec<Uuid>,
        /// The server (world) name, as shown in the messages.
        server: String,
        /// Who the members are pulled to.
        leader: Uuid,
        /// Delay before moving, to avoid moving everyone at once.
        delay: Duration,
        /// Auto-warp: members already on that server stay where they are. `/p warp` pulls everyone.
        skip_if_there: bool,
    },
    /// Membership changed for `affected`.
    PartyChanged {
        affected: Vec<Uuid>,
        /// The party now, or `None` if it was disbanded (or they left it).
        party: Option<Arc<Party>>,
        /// When they left: the party as it was before (for API leave events).
        previous: Option<Arc<Party>>,
    },
}

/// `ids` without duplicates, in first-seen order.
pub(crate) fn distinct(ids: impl IntoIterator<Item = Uuid>) -> Vec<Uuid> {
    let mut out: Vec<Uuid> = Vec::new();
    for id in ids {
        if !out.contains(&id) {
            out.push(id);
        }
    }
    out
}
