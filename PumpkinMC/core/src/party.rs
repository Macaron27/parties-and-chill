//! The party model: `PartyRole`, `Invite`, `PartyMember`, `PartySettings` and `Party`. Parties are immutable
//! snapshots: every change returns a new one, as in Java.

use uuid::Uuid;

/// Epoch milliseconds.
pub type Millis = i64;

/// A member's rank in their party, highest first.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum PartyRole {
    /// The party leader: every right, and the only one who can disband, demote or hand over the party.
    Owner,
    /// Rights set by `roles.moderator` in `config.yml`.
    Moderator,
    /// Rights granted by the party's own settings (`allinvite`, `allwarp`).
    Member,
}

impl PartyRole {
    pub const ALL: [PartyRole; 3] = [PartyRole::Owner, PartyRole::Moderator, PartyRole::Member];

    /// `true` if this role ranks strictly above `other`.
    pub fn outranks(self, other: PartyRole) -> bool {
        self < other
    }

    /// The Java enum constant's name (`OWNER`...), used by the API.
    pub fn name(self) -> &'static str {
        match self {
            PartyRole::Owner => "OWNER",
            PartyRole::Moderator => "MODERATOR",
            PartyRole::Member => "MEMBER",
        }
    }
}

/// A pending party invitation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Invite {
    /// The invited player.
    pub target: Uuid,
    /// The player who sent the invite.
    pub inviter: Uuid,
    /// After this instant the invite can no longer be accepted.
    pub expires_at: Millis,
}

impl Invite {
    /// `true` once `now` has reached [`Invite::expires_at`].
    pub fn expired(&self, now: Millis) -> bool {
        now >= self.expires_at
    }
}

/// One member of a [`Party`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PartyMember {
    pub id: Uuid,
    /// When the player joined the party.
    pub joined_at: Millis,
    /// When a disconnected member is removed; `0` while online.
    pub drop_at: Millis,
    /// The member's rank; [`Party`] keeps it in line with [`Party::leader`].
    pub role: PartyRole,
}

impl PartyMember {
    /// An online member who joined at `now`.
    pub fn joined(id: Uuid, now: Millis) -> PartyMember {
        PartyMember { id, joined_at: now, drop_at: 0, role: PartyRole::Member }
    }

    /// `true` if the member is connected.
    pub fn online(&self) -> bool {
        self.drop_at == 0
    }

    /// A copy flagged as disconnected, to be dropped at `drop_at`.
    pub fn disconnected(self, drop_at: Millis) -> PartyMember {
        PartyMember { drop_at, ..self }
    }

    /// A copy flagged as connected again.
    pub fn reconnected(self) -> PartyMember {
        PartyMember { drop_at: 0, ..self }
    }

    pub fn with_role(self, role: PartyRole) -> PartyMember {
        PartyMember { role, ..self }
    }
}

/// The on/off settings, by the name players type after `/p settings`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Toggle {
    AutoWarp,
    Chat,
    AllInvite,
    AllWarp,
    Public,
    Mute,
}

impl Toggle {
    pub const ALL: [Toggle; 6] =
        [Toggle::AutoWarp, Toggle::Chat, Toggle::AllInvite, Toggle::AllWarp, Toggle::Public, Toggle::Mute];

    /// The command / message key, e.g. `allinvite`.
    pub fn key(self) -> &'static str {
        match self {
            Toggle::AutoWarp => "autowarp",
            Toggle::Chat => "chat",
            Toggle::AllInvite => "allinvite",
            Toggle::AllWarp => "allwarp",
            Toggle::Public => "public",
            Toggle::Mute => "mute",
        }
    }

    /// The toggle named `key` (case-insensitive).
    pub fn by_key(key: &str) -> Option<Toggle> {
        let wanted = key.to_lowercase();
        Toggle::ALL.into_iter().find(|t| t.key() == wanted)
    }
}

/// One party's own settings (`/p settings`). New parties start from `PartyRules::party_defaults`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PartySettings {
    /// Members follow the owner (and moderators allowed to start games) into game servers.
    pub auto_warp: bool,
    /// Party chat is enabled.
    pub chat: bool,
    /// Plain members may invite.
    pub all_invite: bool,
    /// Plain members may `/p warp`.
    pub all_warp: bool,
    /// Anyone may `/p join` without an invite ("public party").
    pub open: bool,
    /// The owner's cap on members; `0` means "as many as the owner's permissions allow".
    pub max_size: u32,
    /// Only the owner and moderators may talk in party chat.
    pub muted: bool,
    /// Minimum delay between two party chat lines of a plain member; `0` is off.
    pub slow_mode_seconds: u32,
}

impl PartySettings {
    /// Longest slow mode an owner can set.
    pub const MAX_SLOW_MODE_SECONDS: u32 = 600;

    /// Built-in defaults.
    pub const DEFAULTS: PartySettings = PartySettings {
        auto_warp: true,
        chat: true,
        all_invite: false,
        all_warp: false,
        open: false,
        max_size: 0,
        muted: false,
        slow_mode_seconds: 0,
    };

    pub fn get(&self, toggle: Toggle) -> bool {
        match toggle {
            Toggle::AutoWarp => self.auto_warp,
            Toggle::Chat => self.chat,
            Toggle::AllInvite => self.all_invite,
            Toggle::AllWarp => self.all_warp,
            Toggle::Public => self.open,
            Toggle::Mute => self.muted,
        }
    }

    /// A copy with `toggle` set to `value`.
    pub fn with(mut self, toggle: Toggle, value: bool) -> PartySettings {
        let field = match toggle {
            Toggle::AutoWarp => &mut self.auto_warp,
            Toggle::Chat => &mut self.chat,
            Toggle::AllInvite => &mut self.all_invite,
            Toggle::AllWarp => &mut self.all_warp,
            Toggle::Public => &mut self.open,
            Toggle::Mute => &mut self.muted,
        };
        *field = value;
        self
    }

    /// How many members the party may have when its owner's permissions allow `limit`.
    pub fn capped_at(&self, limit: u32) -> u32 {
        if self.max_size > 0 { self.max_size.min(limit) } else { limit }
    }

    pub fn with_max_size(self, max_size: u32) -> PartySettings {
        PartySettings { max_size, ..self }
    }

    /// Callers keep `seconds` within [`PartySettings::MAX_SLOW_MODE_SECONDS`].
    pub fn with_slow_mode(self, seconds: u32) -> PartySettings {
        PartySettings { slow_mode_seconds: seconds, ..self }
    }
}

/// Immutable snapshot of a party.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Party {
    /// Stable party identifier.
    pub id: Uuid,
    /// The leader (the [`PartyRole::Owner`]); always one of [`Party::members`].
    pub leader: Uuid,
    /// Members in join order, the leader included.
    pub members: Vec<PartyMember>,
    /// Pending invites, oldest first.
    pub invites: Vec<Invite>,
    pub settings: PartySettings,
}

impl Party {
    /// Builds a party, keeping roles in line with the leader: the leader is the owner, and a previous owner stays on
    /// as moderator (Hypixel's hand-over).
    ///
    /// # Panics
    /// If `leader` is not a member: callers only pass members (a broken invariant, as in Java's constructor).
    pub fn new(
        id: Uuid,
        leader: Uuid,
        members: Vec<PartyMember>,
        invites: Vec<Invite>,
        settings: PartySettings,
    ) -> Party {
        let members: Vec<PartyMember> = members
            .into_iter()
            .map(|m| {
                let role = if m.id == leader {
                    PartyRole::Owner
                } else if m.role == PartyRole::Owner {
                    PartyRole::Moderator
                } else {
                    m.role
                };
                m.with_role(role)
            })
            .collect();
        assert!(members.iter().any(|m| m.id == leader), "leader {leader} is not a member of party {id}");
        Party { id, leader, members, invites, settings }
    }

    /// A party containing only its leader.
    pub fn create(leader: Uuid, now: Millis, settings: PartySettings) -> Party {
        Party::new(Uuid::new_v4(), leader, vec![PartyMember::joined(leader, now)], Vec::new(), settings)
    }

    pub fn member(&self, player: Uuid) -> Option<&PartyMember> {
        self.members.iter().find(|m| m.id == player)
    }

    pub fn is_member(&self, player: Uuid) -> bool {
        self.member(player).is_some()
    }

    pub fn role(&self, player: Uuid) -> Option<PartyRole> {
        self.member(player).map(|m| m.role)
    }

    pub fn is_leader(&self, player: Uuid) -> bool {
        self.leader == player
    }

    /// Members, leader included.
    pub fn size(&self) -> usize {
        self.members.len()
    }

    /// Member ids in join order.
    pub fn member_ids(&self) -> Vec<Uuid> {
        self.members.iter().map(|m| m.id).collect()
    }

    /// Connected members' ids, in join order.
    pub fn online_member_ids(&self) -> Vec<Uuid> {
        self.members.iter().filter(|m| m.online()).map(|m| m.id).collect()
    }

    /// The still-valid invite for `target`.
    pub fn invite_for(&self, target: Uuid, now: Millis) -> Option<&Invite> {
        self.invites.iter().find(|i| i.target == target && !i.expired(now))
    }

    /// The earliest instant this party needs attention (an invite expiring or a disconnected member timing out),
    /// or `Millis::MAX` if nothing is pending.
    pub fn next_deadline(&self) -> Millis {
        let invites = self.invites.iter().map(|i| i.expires_at);
        let members = self.members.iter().filter(|m| !m.online()).map(|m| m.drop_at);
        invites.chain(members).min().unwrap_or(Millis::MAX)
    }

    fn rebuild(&self, leader: Uuid, members: Vec<PartyMember>, invites: Vec<Invite>) -> Party {
        Party::new(self.id, leader, members, invites, self.settings)
    }

    /// A copy with `member` appended, or replaced in place if already present.
    pub fn with_member(&self, member: PartyMember) -> Party {
        let mut members = self.members.clone();
        match members.iter().position(|m| m.id == member.id) {
            Some(index) => members[index] = member,
            None => members.push(member),
        }
        self.rebuild(self.leader, members, self.invites.clone())
    }

    /// A copy where `player`'s entry is transformed by `change`.
    pub fn update_member(&self, player: Uuid, change: impl FnOnce(PartyMember) -> PartyMember) -> Party {
        match self.member(player) {
            Some(member) => self.with_member(change(*member)),
            None => self.clone(),
        }
    }

    /// Removes a member; `next_leader` is the current leader for a regular member, a remaining member when the
    /// leader leaves.
    pub fn without_member(&self, player: Uuid, next_leader: Uuid) -> Party {
        let members = self.members.iter().filter(|m| m.id != player).copied().collect();
        self.rebuild(next_leader, members, self.invites.clone())
    }

    /// A copy led by `new_leader`, who must be a member; the previous owner becomes a moderator.
    pub fn with_leader(&self, new_leader: Uuid) -> Party {
        self.rebuild(new_leader, self.members.clone(), self.invites.clone())
    }

    /// A copy where `player` (not the owner) has `role`.
    pub fn with_role(&self, player: Uuid, role: PartyRole) -> Party {
        self.update_member(player, |m| m.with_role(role))
    }

    pub fn with_settings(&self, settings: PartySettings) -> Party {
        Party { settings, ..self.clone() }
    }

    /// A copy with `invite` added, replacing any previous invite for the same target.
    pub fn with_invite(&self, invite: Invite) -> Party {
        let mut invites: Vec<Invite> = self.invites.iter().filter(|i| i.target != invite.target).copied().collect();
        invites.push(invite);
        self.rebuild(self.leader, self.members.clone(), invites)
    }

    /// A copy without the invite addressed to `target`.
    pub fn without_invite(&self, target: Uuid) -> Party {
        let invites = self.invites.iter().filter(|i| i.target != target).copied().collect();
        self.rebuild(self.leader, self.members.clone(), invites)
    }
}
