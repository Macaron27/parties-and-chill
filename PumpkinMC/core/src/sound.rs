//! `chat.mention.sound` names to sound keys (the Paper bridge's `Sounds`): a namespaced key is used as it is, and
//! the Bukkit `Sound` names that suit a ping (1.8 names included) map to their vanilla keys.

/// Bukkit name → vanilla key (without the `minecraft:` namespace).
const BUKKIT: [(&str, &str); 17] = [
    ("ENTITY_EXPERIENCE_ORB_PICKUP", "entity.experience_orb.pickup"),
    ("ORB_PICKUP", "entity.experience_orb.pickup"),
    ("ENTITY_PLAYER_LEVELUP", "entity.player.levelup"),
    ("LEVEL_UP", "entity.player.levelup"),
    ("BLOCK_NOTE_BLOCK_PLING", "block.note_block.pling"),
    ("BLOCK_NOTE_PLING", "block.note_block.pling"),
    ("NOTE_PLING", "block.note_block.pling"),
    ("BLOCK_NOTE_BLOCK_BELL", "block.note_block.bell"),
    ("BLOCK_NOTE_BLOCK_CHIME", "block.note_block.chime"),
    ("BLOCK_NOTE_BLOCK_HARP", "block.note_block.harp"),
    ("NOTE_PIANO", "block.note_block.harp"),
    ("UI_BUTTON_CLICK", "ui.button.click"),
    ("CLICK", "ui.button.click"),
    ("ENTITY_ITEM_PICKUP", "entity.item.pickup"),
    ("ITEM_PICKUP", "entity.item.pickup"),
    ("ENTITY_ARROW_HIT_PLAYER", "entity.arrow.hit_player"),
    ("SUCCESSFUL_HIT", "entity.arrow.hit_player"),
];

/// The sound key to play for `name`, or `None` for a Bukkit name this table doesn't know.
pub fn key(name: &str) -> Option<String> {
    let name = name.trim();
    if name.contains(':') || name.contains('.') {
        let key = name.to_lowercase();
        return Some(if key.contains(':') { key } else { format!("minecraft:{key}") });
    }
    let upper = name.to_uppercase();
    BUKKIT.iter().find(|(bukkit, _)| *bukkit == upper).map(|(_, key)| format!("minecraft:{key}"))
}
