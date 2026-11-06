//! The `pnc:main` codec, pinned by the golden bytes of Java's `BridgeMessageTest` (and the Paper bridge's
//! `BridgeProtocolTest`): change them together.

use parties_core::bridge::{self, BridgeMessage, PartyInfo};
use uuid::Uuid;

const A: Uuid = Uuid::from_u128(10);
const B: Uuid = Uuid::from_u128(11);
const PARTY_ID: Uuid = Uuid::from_u128(1);

const SNAPSHOT: &str = "010000000000000000000000000000000a010000000000000000000000000000000a000000020000000000000000000000000000000a0000000000000000000000000000000b";
const NO_PARTY: &str = "010000000000000000000000000000000b00";
const CHAT_LOCK: &str = "020000000000000000000000000000000a01";
const HELLO: &str = "0a00000001";
const CHAT: &str = "0b000768c3a9203c623e0000000000000005";
const MUTE: &str = "0c0000000000000000000000000000000b7fffffffffffffff";
const INFO: &str = "000000000000000000000000000000010000000000000000000000000000000a000000020000000000000000000000000000000a0000000000000000000000000000000b01000462772d31";
const REPLY_EMPTY: &str = "03000000080000";
const LEFT: &str = "050000000000000000000000000000000b000000000000000000000000000000010000000000000000000000000000000a000000010000000000000000000000000000000a00";
const HELLO_V2: &str = "0a000000020000001f";
const REQUEST: &str = "0d00000005020000000000000000000000000000000a0000000000000000000000000000000b";
const VERDICT: &str = "0e0000000900";
const SOUND: &str = "060000000000000000000000000000000a00034f52423f00000040000000";
const HELLO_V3: &str = "0a000000030000001f";

fn party() -> PartyInfo {
    PartyInfo { id: PARTY_ID, leader: A, members: vec![A, B], server: Some("bw-1".to_owned()) }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn unhex(text: &str) -> Vec<u8> {
    (0..text.len()).step_by(2).map(|i| u8::from_str_radix(&text[i..i + 2], 16).unwrap()).collect()
}

/// Decodes `hex`, checking it re-encodes to the same bytes.
fn decode(text: &str) -> BridgeMessage {
    let bytes = unhex(text);
    let message = BridgeMessage::decode(&bytes).unwrap();
    assert_eq!(text, hex(&message.encode()));
    message
}

#[test]
fn proxy_to_backend_messages_match_the_golden_bytes() {
    assert_eq!(SNAPSHOT, hex(&BridgeMessage::Snapshot { player: A, leader: Some(A), members: vec![A, B] }.encode()));
    assert_eq!(NO_PARTY, hex(&BridgeMessage::Snapshot { player: B, leader: None, members: vec![] }.encode()));
    assert_eq!(CHAT_LOCK, hex(&BridgeMessage::ChatLock { player: A, locked: true }.encode()));
    let reply = BridgeMessage::Reply { id: 7, status: bridge::STATUS_SUCCESS, party: Some(party()) };
    assert_eq!(format!("03000000070101{INFO}"), hex(&reply.encode()));
    let empty = BridgeMessage::Reply { id: 8, status: bridge::STATUS_FAILED, party: None };
    assert_eq!(REPLY_EMPTY, hex(&empty.encode()));
    let check =
        BridgeMessage::Check { id: 9, event: bridge::EVENT_CHAT, player: B, party: party(), message: "hé".to_owned() };
    assert_eq!(format!("040000000903{}{INFO}000368c3a9", hex(B.as_bytes())), hex(&check.encode()));
    let left =
        BridgeMessage::Left { player: B, party: PartyInfo { id: PARTY_ID, leader: A, members: vec![A], server: None } };
    assert_eq!(LEFT, hex(&left.encode()));
    let sound = BridgeMessage::Sound { player: A, sound: "ORB".to_owned(), volume: 0.5, pitch: 2.0 };
    assert_eq!(SOUND, hex(&sound.encode()));
}

#[test]
fn proxy_to_backend_golden_bytes_decode() {
    assert_eq!(BridgeMessage::Snapshot { player: A, leader: Some(A), members: vec![A, B] }, decode(SNAPSHOT));
    assert_eq!(BridgeMessage::Snapshot { player: B, leader: None, members: vec![] }, decode(NO_PARTY));
    assert_eq!(BridgeMessage::ChatLock { player: A, locked: true }, decode(CHAT_LOCK));
    assert_eq!(
        BridgeMessage::Reply { id: 7, status: bridge::STATUS_SUCCESS, party: Some(party()) },
        decode(&format!("03000000070101{INFO}"))
    );
    assert_eq!(BridgeMessage::Sound { player: A, sound: "ORB".to_owned(), volume: 0.5, pitch: 2.0 }, decode(SOUND));
    match decode(&format!("040000000903{}{INFO}000368c3a9", hex(B.as_bytes()))) {
        BridgeMessage::Check { message, party: info, .. } => assert_eq!(("hé".to_owned(), party()), (message, info)),
        other => panic!("{other:?}"),
    }
}

#[test]
fn backend_to_proxy_golden_bytes() {
    assert_eq!(BridgeMessage::Hello { protocol: 1, listeners: 0 }, decode(HELLO), "protocol 1 bridges still say hello");
    assert_eq!(BridgeMessage::Hello { protocol: 2, listeners: 0x1f }, decode(HELLO_V2));
    assert_eq!(BridgeMessage::Hello { protocol: 3, listeners: 0x1f }, decode(HELLO_V3));
    assert_eq!(
        Ok(BridgeMessage::Hello { protocol: 3, listeners: 0x1f }),
        BridgeMessage::decode(&unhex(&format!("{HELLO_V3}cafe"))),
        "fields appended by newer bridges don't cost the hello"
    );
    assert_eq!(BridgeMessage::Request { id: 5, action: bridge::ACTION_ADD, actor: A, target: B }, decode(REQUEST));
    assert_eq!(BridgeMessage::Verdict { id: 9, allowed: false }, decode(VERDICT));
    assert_eq!(BridgeMessage::Chat { message: "hé <b>".to_owned(), muted_until: 5 }, decode(CHAT));
    assert_eq!(BridgeMessage::Mute { player: B, until: i64::MAX }, decode(MUTE));
    assert_eq!(HELLO_V3, hex(&BridgeMessage::Hello { protocol: bridge::PROTOCOL, listeners: 0x1f }.encode()));
}

#[test]
fn modified_utf8_matches_java() {
    // NUL is two bytes, characters outside the BMP are surrogate pairs of three bytes each.
    let message = BridgeMessage::Chat { message: "a\u{0}😀".to_owned(), muted_until: 0 };
    let bytes = message.encode();
    assert_eq!("0b000961c080eda0bdedb8800000000000000000", hex(&bytes));
    assert_eq!(message, BridgeMessage::decode(&bytes).unwrap());
}

#[test]
fn rejects_garbage() {
    let hello = unhex(HELLO);
    assert!(BridgeMessage::decode(&[]).is_err());
    assert!(BridgeMessage::decode(&[99]).is_err());
    assert!(BridgeMessage::decode(&hello[..3]).is_err());
    let mut longer = unhex(HELLO);
    longer.push(0);
    assert_eq!(Err("trailing bytes in bridge message".to_owned()), BridgeMessage::decode(&longer));
    let huge_count = unhex(&format!("{}7fffffff", &SNAPSHOT[..68]));
    assert_eq!(Err("bad member count".to_owned()), BridgeMessage::decode(&huge_count));
    assert!(BridgeMessage::decode(&unhex("0b0001ff0000000000000000")).is_err(), "malformed modified UTF-8");
}
