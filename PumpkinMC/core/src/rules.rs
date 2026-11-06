//! `PartyRules`: the server-wide party rules loaded from `config.yml`. Each party's own switches live in
//! [`PartySettings`].

use std::collections::HashSet;
use std::time::Duration;

use crate::party::{PartyRole, PartySettings};

/// Largest party size (the backend bridge's member-count limit in Java).
pub const MAX_PARTY_SIZE: u32 = 1024;

/// What a role may do besides chatting. Owners may do everything.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Right {
    Invite,
    Kick,
    Warp,
    /// Promote members to moderator (making someone owner stays the owner's call).
    Promote,
    /// Change the party settings, except the chat ones.
    Settings,
    /// Entering a game server pulls the party along (auto-warp).
    StartGames,
    /// Mute party chat and set slow mode.
    ModerateChat,
}

/// `permission` grants parties of up to `size` members.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SizePermission {
    pub permission: String,
    pub size: u32,
}

impl SizePermission {
    /// # Errors
    /// If `size` is outside 2..=1024.
    pub fn new(permission: impl Into<String>, size: u32) -> Result<SizePermission, String> {
        let permission = permission.into();
        check_size(size, &permission)?;
        Ok(SizePermission { permission, size })
    }
}

#[derive(Clone, Debug)]
pub struct PartyRules {
    /// Members (leader included) for owners without a size permission.
    pub max_size: u32,
    /// How long an invite stays valid.
    pub invite_ttl: Duration,
    /// How long a disconnected member keeps their slot.
    pub disconnect_grace: Duration,
    /// Delay before members follow into a game server.
    pub warp_delay: Duration,
    /// Glob patterns (`*`, `?`) of the servers (on Pumpkin: worlds) that trigger auto-warp.
    pub game_servers: Vec<String>,
    /// Minimum delay between two "I am muted" notices from the same player.
    pub muted_notice_cooldown: Duration,
    /// Bigger parties granted by permission, biggest first.
    pub size_permissions: Vec<SizePermission>,
    /// What moderators may do (`roles.moderator`).
    pub moderator_rights: HashSet<Right>,
    /// Settings every new party starts with.
    pub party_defaults: PartySettings,
}

impl PartyRules {
    /// # Errors
    /// `max-size: party size must be 2..1024, got <n>` (the caller adds the `config.yml: party.` prefix).
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        max_size: u32,
        invite_ttl: Duration,
        disconnect_grace: Duration,
        warp_delay: Duration,
        game_servers: Vec<String>,
        muted_notice_cooldown: Duration,
        mut size_permissions: Vec<SizePermission>,
        moderator_rights: HashSet<Right>,
        party_defaults: PartySettings,
    ) -> Result<PartyRules, String> {
        check_size(max_size, "max-size")?;
        // Biggest first, so the lookup can stop at the first permission the player has.
        size_permissions.sort_by_key(|grant| std::cmp::Reverse(grant.size));
        Ok(PartyRules {
            max_size,
            invite_ttl,
            disconnect_grace,
            warp_delay,
            game_servers,
            muted_notice_cooldown,
            size_permissions,
            moderator_rights,
            party_defaults,
        })
    }

    /// The bundled `config.yml`: 8 players (12/16/24 by permission), 60 s invites, 5 min grace, 1 s warp delay.
    pub fn defaults() -> PartyRules {
        let sizes = [("parties.size.12", 12), ("parties.size.16", 16), ("parties.size.24", 24)];
        PartyRules::new(
            8,
            Duration::from_secs(60),
            Duration::from_secs(300),
            Duration::from_secs(1),
            ["bw-*", "sw-*", "game-*"].map(String::from).to_vec(),
            Duration::from_secs(30),
            sizes.iter().map(|(p, s)| SizePermission { permission: (*p).to_owned(), size: *s }).collect(),
            HashSet::from([Right::Invite, Right::Kick, Right::Warp, Right::StartGames, Right::ModerateChat]),
            PartySettings::DEFAULTS,
        )
        .unwrap_or_else(|e| unreachable!("built-in rules are valid: {e}"))
    }

    /// The party size a player's permissions grant: the biggest [`PartyRules::size_permissions`] entry they have,
    /// never less than [`PartyRules::max_size`].
    pub fn size_limit(&self, has_permission: impl Fn(&str) -> bool) -> u32 {
        for grant in &self.size_permissions {
            if grant.size <= self.max_size {
                break; // sorted: nothing further down beats the default
            }
            if has_permission(&grant.permission) {
                return grant.size;
            }
        }
        self.max_size
    }

    /// `true` if `role` has `right` in any party (members get theirs from the party settings).
    pub fn grants(&self, role: PartyRole, right: Right) -> bool {
        role == PartyRole::Owner || role == PartyRole::Moderator && self.moderator_rights.contains(&right)
    }

    /// `true` if a [`PartyRules::game_servers`] pattern matches `server` (case-insensitive).
    pub fn is_game_server(&self, server: &str) -> bool {
        let name: Vec<char> = server.to_lowercase().chars().collect();
        self.game_servers.iter().any(|glob| {
            let pattern: Vec<char> = glob.to_lowercase().chars().collect();
            glob_matches(&pattern, &name)
        })
    }
}

fn check_size(size: u32, what: &str) -> Result<(), String> {
    if (2..=MAX_PARTY_SIZE).contains(&size) {
        Ok(())
    } else {
        Err(format!("{what}: party size must be 2..{MAX_PARTY_SIZE}, got {size}"))
    }
}

/// `*` matches any run of characters, `?` exactly one; everything else literally.
fn glob_matches(pattern: &[char], name: &[char]) -> bool {
    let (mut p, mut n) = (0, 0);
    let mut backtrack: Option<(usize, usize)> = None;
    while n < name.len() {
        if p < pattern.len() && (pattern[p] == '?' || pattern[p] == name[n]) {
            p += 1;
            n += 1;
        } else if p < pattern.len() && pattern[p] == '*' {
            backtrack = Some((p, n));
            p += 1;
        } else if let Some((star, matched)) = backtrack {
            p = star + 1;
            n = matched + 1;
            backtrack = Some((star, matched + 1));
        } else {
            return false;
        }
    }
    pattern[p..].iter().all(|&c| c == '*')
}
