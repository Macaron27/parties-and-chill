//! `LocalNetwork`: who is online, their names, servers (on Pumpkin: worlds), party size limits and mutes.
//! Everything lives in this plugin; players who left recently keep a name (and size limit), so `/p list` and kick
//! still work during the disconnect grace.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;

use uuid::Uuid;

use crate::durations;
use crate::manager::{Clock, lock};
use crate::party::Millis;

#[derive(Default)]
struct State {
    online: HashMap<Uuid, String>,
    online_by_name: HashMap<String, Uuid>,
    servers: HashMap<Uuid, String>,
    /// Departed players: name and expiry.
    recent_names: HashMap<Uuid, (String, Millis)>,
    recent_by_name: HashMap<String, (Uuid, Millis)>,
    size_limits: HashMap<Uuid, u32>,
    recent_size_limits: HashMap<Uuid, (u32, Millis)>,
    mutes: HashMap<Uuid, Millis>,
}

pub struct LocalNetwork {
    state: Mutex<State>,
    clock: Clock,
    /// How long a departed player's name stays resolvable (above the disconnect grace).
    retention: Duration,
}

impl LocalNetwork {
    pub fn new(clock: Clock, retention: Duration) -> LocalNetwork {
        LocalNetwork { state: Mutex::new(State::default()), clock, retention }
    }

    fn now(&self) -> Millis {
        (self.clock)()
    }

    /// Registers a player who just joined, with the party size their permissions grant.
    pub fn player_joined(&self, id: Uuid, name: &str, size_limit: u32) {
        let mut state = lock(&self.state);
        state.size_limits.insert(id, size_limit);
        if let Some(previous) = state.online.insert(id, name.to_owned()) {
            let key = previous.to_lowercase();
            if state.online_by_name.get(&key) == Some(&id) {
                state.online_by_name.remove(&key);
            }
        }
        state.online_by_name.insert(name.to_lowercase(), id);
    }

    /// Unregisters a player who left.
    pub fn player_left(&self, id: Uuid) {
        let expires = self.now() + durations::millis(self.retention);
        let mut state = lock(&self.state);
        if let Some(limit) = state.size_limits.remove(&id) {
            state.recent_size_limits.insert(id, (limit, expires));
        }
        state.servers.remove(&id);
        let Some(name) = state.online.remove(&id) else { return };
        let key = name.to_lowercase();
        if state.online_by_name.get(&key) == Some(&id) {
            state.online_by_name.remove(&key);
        }
        state.recent_by_name.insert(key, (id, expires));
        state.recent_names.insert(id, (name, expires));
    }

    /// Records the server (world) an online player is on; ignored once they left.
    pub fn server_switched(&self, id: Uuid, server: &str) {
        let mut state = lock(&self.state);
        if state.online.contains_key(&id) {
            state.servers.insert(id, server.to_owned());
        }
    }

    pub fn server_of(&self, id: Uuid) -> Option<String> {
        lock(&self.state).servers.get(&id).cloned()
    }

    pub fn is_online(&self, id: Uuid) -> bool {
        lock(&self.state).online.contains_key(&id)
    }

    /// Online players' ids.
    pub fn online(&self) -> Vec<Uuid> {
        lock(&self.state).online.keys().copied().collect()
    }

    /// A player seen recently, by case-insensitive name.
    pub fn uuid_of(&self, name: &str) -> Option<Uuid> {
        let key = name.to_lowercase();
        let now = self.now();
        let state = lock(&self.state);
        state
            .online_by_name
            .get(&key)
            .copied()
            .or_else(|| state.recent_by_name.get(&key).filter(|(_, expires)| *expires > now).map(|(id, _)| *id))
    }

    /// The last known name of a player.
    pub fn name_of(&self, id: Uuid) -> Option<String> {
        let now = self.now();
        let state = lock(&self.state);
        state
            .online
            .get(&id)
            .cloned()
            .or_else(|| state.recent_names.get(&id).filter(|(_, expires)| *expires > now).map(|(name, _)| name.clone()))
    }

    /// An online player, by case-insensitive name.
    pub fn find_online(&self, name: &str) -> Option<Uuid> {
        lock(&self.state).online_by_name.get(&name.to_lowercase()).copied()
    }

    /// The party size limit recorded for the player (at login, refreshed while online), or `0` if unknown.
    pub fn size_limit(&self, id: Uuid) -> u32 {
        let now = self.now();
        let state = lock(&self.state);
        state.size_limits.get(&id).copied().unwrap_or_else(|| {
            state.recent_size_limits.get(&id).filter(|(_, expires)| *expires > now).map_or(0, |(limit, _)| *limit)
        })
    }

    /// Updates an online player's size limit (their permissions changed).
    pub fn set_size_limit(&self, id: Uuid, limit: u32) {
        let mut state = lock(&self.state);
        if state.online.contains_key(&id) {
            state.size_limits.insert(id, limit);
        }
    }

    /// Until when the player is muted (`0`: not muted, `Millis::MAX`: forever).
    pub fn muted_until(&self, id: Uuid) -> Millis {
        let now = self.now();
        let mut state = lock(&self.state);
        match state.mutes.get(&id).copied() {
            Some(until) if until > now => until,
            Some(_) => {
                state.mutes.remove(&id);
                0
            }
            None => 0,
        }
    }

    /// Records a mute; `0` (or any past instant) lifts it.
    pub fn set_muted_until(&self, id: Uuid, until: Millis) {
        let mut state = lock(&self.state);
        if until <= 0 {
            state.mutes.remove(&id);
        } else {
            state.mutes.insert(id, until);
        }
    }

    /// Forgets expired names and size limits (Caffeine's expiry in Java).
    pub fn prune(&self) {
        let now = self.now();
        let mut state = lock(&self.state);
        state.recent_names.retain(|_, (_, expires)| *expires > now);
        state.recent_by_name.retain(|_, (_, expires)| *expires > now);
        state.recent_size_limits.retain(|_, (_, expires)| *expires > now);
        state.mutes.retain(|_, until| *until > now);
    }
}
