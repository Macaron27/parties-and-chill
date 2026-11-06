//! `InMemoryPartyStore`: where party snapshots live on a Pumpkin server. [`crate::manager::PartyManager`] owns it
//! behind a lock, which is what Java's `atomically` provides.

use std::collections::HashMap;
use std::sync::Arc;

use uuid::Uuid;

use crate::party::{Millis, Party};

#[derive(Default)]
pub struct InMemoryPartyStore {
    parties: HashMap<Uuid, Arc<Party>>,
    party_by_member: HashMap<Uuid, Uuid>,
    /// Only parties with a pending invite or a disconnected member: the sweep never touches idle parties.
    deadlines: HashMap<Uuid, Millis>,
}

impl InMemoryPartyStore {
    /// The party `player` belongs to.
    pub fn by_member(&self, player: Uuid) -> Option<Arc<Party>> {
        self.party_by_member.get(&player).and_then(|id| self.parties.get(id)).cloned()
    }

    /// Parties whose [`Party::next_deadline`] is at or before `now`.
    pub fn due(&self, now: Millis) -> Vec<Arc<Party>> {
        let mut due: Vec<Arc<Party>> = self
            .deadlines
            .iter()
            .filter(|(_, deadline)| **deadline <= now)
            .filter_map(|(id, _)| self.parties.get(id))
            .filter(|p| p.next_deadline() <= now)
            .cloned()
            .collect();
        due.sort_by_key(|p| p.next_deadline()); // deterministic order
        due
    }

    /// `true` if [`InMemoryPartyStore::due`] would return something; the 1 s sweep checks this first.
    pub fn any_due(&self, now: Millis) -> bool {
        self.deadlines.values().any(|d| *d <= now)
    }

    /// Every party (the API and the size-limit refresh read them).
    pub fn all(&self) -> Vec<Arc<Party>> {
        self.parties.values().cloned().collect()
    }

    /// Inserts or replaces `party` and re-indexes its members; returns the stored snapshot.
    pub fn save(&mut self, party: Party) -> Arc<Party> {
        let id = party.id;
        if let Some(previous) = self.parties.get(&id) {
            for member in previous.member_ids() {
                if !party.is_member(member) && self.party_by_member.get(&member) == Some(&id) {
                    self.party_by_member.remove(&member);
                }
            }
        }
        for member in party.member_ids() {
            self.party_by_member.insert(member, id);
        }
        match party.next_deadline() {
            Millis::MAX => self.deadlines.remove(&id),
            deadline => self.deadlines.insert(id, deadline),
        };
        let party = Arc::new(party);
        self.parties.insert(id, party.clone());
        party
    }

    /// Removes `party` and its member index entries.
    pub fn delete(&mut self, party: &Party) {
        self.deadlines.remove(&party.id);
        if let Some(previous) = self.parties.remove(&party.id) {
            for member in previous.member_ids() {
                if self.party_by_member.get(&member) == Some(&party.id) {
                    self.party_by_member.remove(&member);
                }
            }
        }
    }
}
