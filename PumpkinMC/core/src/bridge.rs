//! `BridgeMessage`: plugin messages exchanged between Parties & Chill proxies and backend bridges on channel
//! `pnc:main`. The wire format is a type byte followed by Java `DataOutputStream` fields (big-endian numbers,
//! `writeUTF` strings), pinned by the same golden bytes as the Java proxy and Paper bridge tests.

use uuid::Uuid;

/// Short enough for 1.8's 20-character channel limit, namespaced for 1.13+.
pub const CHANNEL: &str = "pnc:main";
/// The protocol a bridge speaks: 2 added the developer API, 3 sounds (party chat mentions).
pub const PROTOCOL: i32 = 3;
pub const MAX_MEMBERS: usize = 1024;

/// Backend events: [`BridgeMessage::Check`] codes, and [`BridgeMessage::Hello`] listener bits (`1 << code`).
pub const EVENT_CREATE: u8 = 0;
pub const EVENT_JOIN: u8 = 1;
pub const EVENT_DISBAND: u8 = 2;
pub const EVENT_CHAT: u8 = 3;
pub const EVENT_LEAVE: u8 = 4;
/// [`BridgeMessage::Request`] actions (developer API calls).
pub const ACTION_GET: u8 = 0;
pub const ACTION_CREATE: u8 = 1;
pub const ACTION_ADD: u8 = 2;
pub const ACTION_REMOVE: u8 = 3;
pub const ACTION_DISBAND: u8 = 4;
/// [`BridgeMessage::Reply`] statuses.
pub const STATUS_FAILED: u8 = 0;
pub const STATUS_SUCCESS: u8 = 1;
pub const STATUS_ERROR: u8 = 2;

/// A party as backend plugins see it; `server` is the leader's, `None` when they are offline.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PartyInfo {
    pub id: Uuid,
    pub leader: Uuid,
    pub members: Vec<Uuid>,
    pub server: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum BridgeMessage {
    // proxy → backend
    /// The party of `player`: `leader == None` means "not in a party".
    Snapshot { player: Uuid, leader: Option<Uuid>, members: Vec<Uuid> },
    /// Whether the backend must divert `player`'s chat into party chat.
    ChatLock { player: Uuid, locked: bool },
    /// Answer to request `id`; `party` only for [`ACTION_GET`] when there is one.
    Reply { id: i32, status: u8, party: Option<PartyInfo> },
    /// Asks whether the backend's plugins allow `event`; answered by [`BridgeMessage::Verdict`].
    Check { id: i32, event: u8, player: Uuid, party: PartyInfo, message: String },
    /// `player` is no longer in `party` (shown as it was before).
    Left { player: Uuid, party: PartyInfo },
    /// Plays `sound` (a Bukkit `Sound` name or a namespaced key) to `player`.
    Sound { player: Uuid, sound: String, volume: f32, pitch: f32 },
    // backend → proxy
    /// Sent when a player joins a backend running the bridge; `listeners`: events backend plugins listen to.
    Hello { protocol: i32, listeners: i32 },
    /// A chat line the backend diverted (the sender is the connection it came through).
    Chat { message: String, muted_until: i64 },
    /// A mute change (`until == 0`: unmuted).
    Mute { player: Uuid, until: i64 },
    /// A developer API call; the proxy answers with [`BridgeMessage::Reply`] `id` on the same connection.
    Request { id: i32, action: u8, actor: Uuid, target: Uuid },
    /// Answer to [`BridgeMessage::Check`] `id`.
    Verdict { id: i32, allowed: bool },
}

impl BridgeMessage {
    /// The wire form.
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Writer(Vec::new());
        match self {
            BridgeMessage::Snapshot { player, leader, members } => {
                out.byte(1);
                out.uuid(*player);
                out.bool(leader.is_some());
                if let Some(leader) = leader {
                    out.uuid(*leader);
                    out.uuids(members);
                }
            }
            BridgeMessage::ChatLock { player, locked } => {
                out.byte(2);
                out.uuid(*player);
                out.bool(*locked);
            }
            BridgeMessage::Reply { id, status, party } => {
                out.byte(3);
                out.int(*id);
                out.byte(*status);
                out.bool(party.is_some());
                if let Some(party) = party {
                    out.party(party);
                }
            }
            BridgeMessage::Check { id, event, player, party, message } => {
                out.byte(4);
                out.int(*id);
                out.byte(*event);
                out.uuid(*player);
                out.party(party);
                out.utf(message);
            }
            BridgeMessage::Left { player, party } => {
                out.byte(5);
                out.uuid(*player);
                out.party(party);
            }
            BridgeMessage::Sound { player, sound, volume, pitch } => {
                out.byte(6);
                out.uuid(*player);
                out.utf(sound);
                out.0.extend_from_slice(&volume.to_bits().to_be_bytes());
                out.0.extend_from_slice(&pitch.to_bits().to_be_bytes());
            }
            BridgeMessage::Hello { protocol, listeners } => {
                out.byte(10);
                out.int(*protocol);
                if *protocol >= 2 {
                    out.int(*listeners);
                }
            }
            BridgeMessage::Chat { message, muted_until } => {
                out.byte(11);
                out.utf(message);
                out.long(*muted_until);
            }
            BridgeMessage::Mute { player, until } => {
                out.byte(12);
                out.uuid(*player);
                out.long(*until);
            }
            BridgeMessage::Request { id, action, actor, target } => {
                out.byte(13);
                out.int(*id);
                out.byte(*action);
                out.uuid(*actor);
                out.uuid(*target);
            }
            BridgeMessage::Verdict { id, allowed } => {
                out.byte(14);
                out.int(*id);
                out.bool(*allowed);
            }
        }
        out.0
    }

    /// # Errors
    /// `unknown bridge message type`, `truncated bridge message`, `trailing bytes in bridge message`, `bad member
    /// count`.
    pub fn decode(data: &[u8]) -> Result<BridgeMessage, String> {
        let mut r = Reader { data, pos: 0 };
        let message = match r.byte()? {
            1 => {
                let player = r.uuid()?;
                if r.bool()? {
                    let leader = r.uuid()?;
                    BridgeMessage::Snapshot { player, leader: Some(leader), members: r.uuids()? }
                } else {
                    BridgeMessage::Snapshot { player, leader: None, members: Vec::new() }
                }
            }
            2 => BridgeMessage::ChatLock { player: r.uuid()?, locked: r.bool()? },
            3 => {
                let id = r.int()?;
                let status = r.byte()?;
                let party = if r.bool()? { Some(r.party()?) } else { None };
                BridgeMessage::Reply { id, status, party }
            }
            4 => BridgeMessage::Check {
                id: r.int()?,
                event: r.byte()?,
                player: r.uuid()?,
                party: r.party()?,
                message: r.utf()?,
            },
            5 => BridgeMessage::Left { player: r.uuid()?, party: r.party()? },
            6 => BridgeMessage::Sound {
                player: r.uuid()?,
                sound: r.utf()?,
                volume: f32::from_bits(r.int()? as u32),
                pitch: f32::from_bits(r.int()? as u32),
            },
            10 => {
                let protocol = r.int()?;
                let listeners = if protocol >= 2 { r.int()? } else { 0 };
                if protocol > 2 {
                    r.pos = data.len(); // newer bridges may append fields: keep the hello
                }
                BridgeMessage::Hello { protocol, listeners }
            }
            11 => BridgeMessage::Chat { message: r.utf()?, muted_until: r.long()? },
            12 => BridgeMessage::Mute { player: r.uuid()?, until: r.long()? },
            13 => BridgeMessage::Request { id: r.int()?, action: r.byte()?, actor: r.uuid()?, target: r.uuid()? },
            14 => BridgeMessage::Verdict { id: r.int()?, allowed: r.bool()? },
            _ => return Err("unknown bridge message type".to_owned()),
        };
        if r.pos < data.len() {
            return Err("trailing bytes in bridge message".to_owned());
        }
        Ok(message)
    }
}

struct Writer(Vec<u8>);

impl Writer {
    fn byte(&mut self, value: u8) {
        self.0.push(value);
    }

    fn bool(&mut self, value: bool) {
        self.0.push(u8::from(value));
    }

    fn int(&mut self, value: i32) {
        self.0.extend_from_slice(&value.to_be_bytes());
    }

    fn long(&mut self, value: i64) {
        self.0.extend_from_slice(&value.to_be_bytes());
    }

    fn uuid(&mut self, id: Uuid) {
        self.0.extend_from_slice(id.as_bytes()); // most significant long, then least: big-endian bytes
    }

    fn uuids(&mut self, ids: &[Uuid]) {
        self.int(i32::try_from(ids.len()).unwrap_or(i32::MAX));
        for id in ids {
            self.uuid(*id);
        }
    }

    /// `DataOutputStream.writeUTF`: a 2-byte length, then modified UTF-8 (NUL as two bytes, supplementary
    /// characters as surrogate pairs). Strings over 65535 bytes are cut at a character boundary (Java throws).
    fn utf(&mut self, text: &str) {
        let mut bytes = Vec::with_capacity(text.len());
        for unit in text.encode_utf16() {
            let mut encoded = Vec::with_capacity(3);
            match unit {
                0x0001..=0x007F => encoded.push(unit as u8),
                0x0000 | 0x0080..=0x07FF => {
                    encoded.push(0xC0 | (unit >> 6) as u8);
                    encoded.push(0x80 | (unit & 0x3F) as u8);
                }
                _ => {
                    encoded.push(0xE0 | (unit >> 12) as u8);
                    encoded.push(0x80 | ((unit >> 6) & 0x3F) as u8);
                    encoded.push(0x80 | (unit & 0x3F) as u8);
                }
            }
            if bytes.len() + encoded.len() > usize::from(u16::MAX) {
                break;
            }
            bytes.extend_from_slice(&encoded);
        }
        self.0.extend_from_slice(&(bytes.len() as u16).to_be_bytes());
        self.0.extend_from_slice(&bytes);
    }

    fn party(&mut self, party: &PartyInfo) {
        self.uuid(party.id);
        self.uuid(party.leader);
        self.uuids(&party.members);
        self.bool(party.server.is_some());
        if let Some(server) = &party.server {
            self.utf(server);
        }
    }
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl Reader<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8], String> {
        let end = self.pos.checked_add(n).filter(|end| *end <= self.data.len());
        let Some(end) = end else { return Err("truncated bridge message".to_owned()) };
        let bytes = &self.data[self.pos..end];
        self.pos = end;
        Ok(bytes)
    }

    fn byte(&mut self) -> Result<u8, String> {
        Ok(self.take(1)?[0])
    }

    fn bool(&mut self) -> Result<bool, String> {
        Ok(self.byte()? != 0)
    }

    fn int(&mut self) -> Result<i32, String> {
        let bytes = self.take(4)?;
        Ok(i32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    fn long(&mut self) -> Result<i64, String> {
        let mut buffer = [0; 8];
        buffer.copy_from_slice(self.take(8)?);
        Ok(i64::from_be_bytes(buffer))
    }

    fn uuid(&mut self) -> Result<Uuid, String> {
        let mut buffer = [0; 16];
        buffer.copy_from_slice(self.take(16)?);
        Ok(Uuid::from_bytes(buffer))
    }

    fn uuids(&mut self) -> Result<Vec<Uuid>, String> {
        let count = self.int()?;
        let count = usize::try_from(count).ok().filter(|c| *c <= MAX_MEMBERS);
        let Some(count) = count else { return Err("bad member count".to_owned()) };
        (0..count).map(|_| self.uuid()).collect()
    }

    /// `DataInputStream.readUTF`.
    fn utf(&mut self) -> Result<String, String> {
        let length = self.take(2)?;
        let length = usize::from(u16::from_be_bytes([length[0], length[1]]));
        let bytes = self.take(length)?;
        let malformed = || "malformed string in bridge message".to_owned();
        let mut units = Vec::with_capacity(length);
        let mut i = 0;
        while i < bytes.len() {
            let b = bytes[i];
            let (unit, size) = match b >> 4 {
                0..=7 => (u16::from(b), 1),
                12 | 13 => {
                    let b2 = *bytes.get(i + 1).ok_or_else(malformed)?;
                    if b2 & 0xC0 != 0x80 {
                        return Err(malformed());
                    }
                    ((u16::from(b & 0x1F) << 6) | u16::from(b2 & 0x3F), 2)
                }
                14 => {
                    let (b2, b3) = (*bytes.get(i + 1).ok_or_else(malformed)?, *bytes.get(i + 2).ok_or_else(malformed)?);
                    if b2 & 0xC0 != 0x80 || b3 & 0xC0 != 0x80 {
                        return Err(malformed());
                    }
                    ((u16::from(b & 0x0F) << 12) | (u16::from(b2 & 0x3F) << 6) | u16::from(b3 & 0x3F), 3)
                }
                _ => return Err(malformed()),
            };
            units.push(unit);
            i += size;
        }
        Ok(String::from_utf16_lossy(&units))
    }

    fn party(&mut self) -> Result<PartyInfo, String> {
        let id = self.uuid()?;
        let leader = self.uuid()?;
        let members = self.uuids()?;
        let server = if self.bool()? { Some(self.utf()?) } else { None };
        Ok(PartyInfo { id, leader, members, server })
    }
}
