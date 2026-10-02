package dev.partiesandchill.bridge;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Plays the sounds the proxy asks for (party chat mentions) on any server from 1.8.8 to 26.x.
 *
 * <p>Names are read as {@code Sound} constants by reflection: that works whether {@code Sound} is an enum (up to
 * 1.21.2) or an interface with constants (1.21.3+), where a compiled {@code Sound.valueOf} call would break. Sounds
 * renamed in 1.9 are mapped both ways, and anything with a {@code :} or {@code .} is played as a namespaced key.
 */
final class Sounds {

    /** Modern ↔ 1.8 names of the sounds that suit a ping. */
    private static final Map<String, String> ALIASES = new HashMap<String, String>();

    static {
        alias("ENTITY_EXPERIENCE_ORB_PICKUP", "ORB_PICKUP");
        alias("ENTITY_PLAYER_LEVELUP", "LEVEL_UP");
        alias("BLOCK_NOTE_BLOCK_PLING", "NOTE_PLING");
        alias("BLOCK_NOTE_PLING", "NOTE_PLING"); // 1.9 – 1.12 name
        alias("UI_BUTTON_CLICK", "CLICK");
        alias("ENTITY_ITEM_PICKUP", "ITEM_PICKUP");
    }

    /** Resolved names; {@link #MISSING} for names this server doesn't know. */
    private final Map<String, Object> resolved = new ConcurrentHashMap<String, Object>();
    private static final Object MISSING = new Object();
    private final Logger logger;

    Sounds(Logger logger) {
        this.logger = logger;
    }

    private static void alias(String modern, String legacy) {
        ALIASES.put(modern, legacy);
        if (!ALIASES.containsKey(legacy)) ALIASES.put(legacy, modern);
    }

    /** Plays {@code name} at {@code player}'s position; unknown names are logged once and skipped. */
    void play(Player player, String name, float volume, float pitch) {
        if (name.indexOf(':') >= 0 || name.indexOf('.') >= 0) {
            player.playSound(player.getLocation(), name.toLowerCase(Locale.ROOT), volume, pitch);
            return;
        }
        Sound sound = resolve(name);
        if (sound != null) player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /** @return the constant named {@code name} (or its 1.8 / modern alias), {@code null} if this server has neither */
    Sound resolve(String name) {
        Object sound = resolved.get(name);
        if (sound == null) {
            String upper = name.toUpperCase(Locale.ROOT);
            sound = constant(upper);
            if (sound == null && ALIASES.containsKey(upper)) sound = constant(ALIASES.get(upper));
            if (sound == null) {
                logger.warning("Unknown sound '" + name + "' (chat.mention.sound in the proxy's config.yml); "
                        + "use a Bukkit Sound name or a namespaced key");
                sound = MISSING;
            }
            resolved.put(name, sound);
        }
        return sound == MISSING ? null : (Sound) sound;
    }

    private static Sound constant(String name) {
        try {
            Object value = Sound.class.getField(name).get(null);
            return value instanceof Sound ? (Sound) value : null;
        } catch (NoSuchFieldException e) {
            return null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }
}
