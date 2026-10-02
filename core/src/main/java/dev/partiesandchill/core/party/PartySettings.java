package dev.partiesandchill.core.party;

import java.util.Locale;
import java.util.Optional;

/**
 * One party's own settings ({@code /p settings}). New parties start from {@link PartyRules#partyDefaults()}.
 *
 * @param autoWarp        members follow the owner (and moderators allowed to start games) into game servers
 * @param chat            party chat is enabled
 * @param allInvite       plain members may invite
 * @param allWarp         plain members may {@code /p warp}
 * @param open            anyone may {@code /p join} without an invite ("public party")
 * @param maxSize         the owner's cap on members; {@code 0} means "as many as the owner's permissions allow"
 * @param muted           only the owner and moderators may talk in party chat
 * @param slowModeSeconds minimum delay between two party chat lines of a plain member; {@code 0} is off
 */
public record PartySettings(boolean autoWarp, boolean chat, boolean allInvite, boolean allWarp, boolean open,
                            int maxSize, boolean muted, int slowModeSeconds) {

    /** Longest slow mode an owner can set. */
    public static final int MAX_SLOW_MODE_SECONDS = 600;

    /** Built-in defaults, also used for parties stored by 1.2 (which had no settings). */
    public static final PartySettings DEFAULTS = new PartySettings(true, true, false, false, false, 0, false, 0);

    public PartySettings {
        if (maxSize < 0) throw new IllegalArgumentException("maxSize must be >= 0, got " + maxSize);
        if (slowModeSeconds < 0 || slowModeSeconds > MAX_SLOW_MODE_SECONDS) {
            throw new IllegalArgumentException("slow mode must be 0.." + MAX_SLOW_MODE_SECONDS + " s, got " + slowModeSeconds);
        }
    }

    /** The on/off settings, by the name players type after {@code /p settings}. */
    public enum Toggle {
        AUTO_WARP("autowarp"), CHAT("chat"), ALL_INVITE("allinvite"), ALL_WARP("allwarp"), PUBLIC("public"), MUTE("mute");

        private final String key;

        Toggle(String key) {
            this.key = key;
        }

        /** @return the command / message key, e.g. {@code allinvite} */
        public String key() {
            return key;
        }

        /** @return the toggle named {@code key} (case-insensitive) */
        public static Optional<Toggle> byKey(String key) {
            String wanted = key.toLowerCase(Locale.ROOT);
            for (Toggle toggle : values()) if (toggle.key.equals(wanted)) return Optional.of(toggle);
            return Optional.empty();
        }
    }

    /** @return the current value of {@code toggle} */
    public boolean get(Toggle toggle) {
        return switch (toggle) {
            case AUTO_WARP -> autoWarp;
            case CHAT -> chat;
            case ALL_INVITE -> allInvite;
            case ALL_WARP -> allWarp;
            case PUBLIC -> open;
            case MUTE -> muted;
        };
    }

    /** @return a copy with {@code toggle} set to {@code value} */
    public PartySettings with(Toggle toggle, boolean value) {
        return new PartySettings(
                toggle == Toggle.AUTO_WARP ? value : autoWarp,
                toggle == Toggle.CHAT ? value : chat,
                toggle == Toggle.ALL_INVITE ? value : allInvite,
                toggle == Toggle.ALL_WARP ? value : allWarp,
                toggle == Toggle.PUBLIC ? value : open,
                maxSize,
                toggle == Toggle.MUTE ? value : muted,
                slowModeSeconds);
    }

    /** @return how many members the party may have when its owner's permissions allow {@code limit} */
    public int cappedAt(int limit) {
        return maxSize > 0 ? Math.min(maxSize, limit) : limit;
    }

    /** @return a copy capped at {@code maxSize} members ({@code 0}: the owner's permission limit) */
    public PartySettings withMaxSize(int maxSize) {
        return new PartySettings(autoWarp, chat, allInvite, allWarp, open, maxSize, muted, slowModeSeconds);
    }

    /** @return a copy with slow mode set to {@code seconds} ({@code 0}: off) */
    public PartySettings withSlowMode(int seconds) {
        return new PartySettings(autoWarp, chat, allInvite, allWarp, open, maxSize, muted, seconds);
    }
}
