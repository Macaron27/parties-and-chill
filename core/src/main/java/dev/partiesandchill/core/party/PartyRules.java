package dev.partiesandchill.core.party;

import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Network-wide party rules, loaded from {@code config.yml}. Each party's own switches live in {@link PartySettings}.
 *
 * @param maxSize             members (leader included) for owners without a size permission
 * @param inviteTtl           how long an invite stays valid
 * @param disconnectGrace     how long a disconnected member keeps their slot before being dropped
 * @param warpDelay           delay before members follow into a game server
 * @param gameServers         glob patterns ({@code *}, {@code ?}) of servers that trigger auto-warp
 * @param mutedNoticeCooldown minimum delay between two "I am muted" notices from the same player
 * @param sizePermissions     bigger parties granted by permission (e.g. {@code parties.size.16}); the highest wins
 * @param moderatorRights     what moderators may do ({@code roles.moderator})
 * @param partyDefaults       settings every new party starts with
 */
public record PartyRules(int maxSize, Duration inviteTtl, Duration disconnectGrace, Duration warpDelay,
                         List<String> gameServers, Duration mutedNoticeCooldown, List<SizePermission> sizePermissions,
                         Set<Right> moderatorRights, PartySettings partyDefaults) {

    /** Largest party the backend bridge accepts (its member-count limit). */
    public static final int MAX_PARTY_SIZE = 1024;

    /** Compiled globs; the set of patterns is tiny and fixed by config. */
    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    /** What a role may do besides chatting. Owners may do everything. */
    public enum Right {
        INVITE, KICK, WARP,
        /** Promote members to moderator (making someone owner stays the owner's call). */
        PROMOTE,
        /** Change the party settings, except the chat ones. */
        SETTINGS,
        /** Entering a game server pulls the party along (auto-warp). */
        START_GAMES,
        /** Mute party chat and set slow mode. */
        MODERATE_CHAT
    }

    /** {@code permission} grants parties of up to {@code size} members. */
    public record SizePermission(String permission, int size) {
        public SizePermission {
            Objects.requireNonNull(permission, "permission");
            checkSize(size, permission);
        }
    }

    public PartyRules {
        checkSize(maxSize, "max-size");
        Objects.requireNonNull(inviteTtl, "inviteTtl");
        Objects.requireNonNull(disconnectGrace, "disconnectGrace");
        Objects.requireNonNull(warpDelay, "warpDelay");
        Objects.requireNonNull(mutedNoticeCooldown, "mutedNoticeCooldown");
        Objects.requireNonNull(partyDefaults, "partyDefaults");
        gameServers = List.copyOf(gameServers);
        // Biggest first, so the lookup can stop at the first permission the player has.
        sizePermissions = sizePermissions.stream()
                .sorted(Comparator.comparingInt(SizePermission::size).reversed()).toList();
        moderatorRights = Set.copyOf(moderatorRights);
    }

    /** The bundled {@code config.yml}: 8 players (12/16/24 by permission), 60 s invites, 5 min grace, 1 s warp delay. */
    public static PartyRules defaults() {
        return new PartyRules(8, Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofSeconds(1),
                List.of("bw-*", "sw-*", "game-*"), Duration.ofSeconds(30),
                List.of(new SizePermission("parties.size.12", 12), new SizePermission("parties.size.16", 16),
                        new SizePermission("parties.size.24", 24)),
                EnumSet.of(Right.INVITE, Right.KICK, Right.WARP, Right.START_GAMES, Right.MODERATE_CHAT),
                PartySettings.DEFAULTS);
    }

    /**
     * The party size a player's permissions grant: the biggest {@link #sizePermissions()} entry they have, never
     * less than {@link #maxSize()}.
     *
     * @param hasPermission the player's permission check (e.g. backed by LuckPerms)
     */
    public int sizeLimit(Predicate<String> hasPermission) {
        for (SizePermission grant : sizePermissions) {
            if (grant.size() <= maxSize) break; // sorted: nothing further down beats the default
            if (hasPermission.test(grant.permission())) return grant.size();
        }
        return maxSize;
    }

    /** @return {@code true} if {@code role} has {@code right} in any party (members get theirs from the party settings) */
    public boolean grants(PartyRole role, Right right) {
        return role == PartyRole.OWNER || role == PartyRole.MODERATOR && moderatorRights.contains(right);
    }

    /**
     * Tells whether joining {@code server} should pull the party along.
     *
     * @param server backend server name as registered on the proxy
     * @return {@code true} if a {@link #gameServers()} pattern matches (case-insensitive)
     */
    public boolean isGameServer(String server) {
        String name = server.toLowerCase(Locale.ROOT);
        for (String glob : gameServers) {
            if (COMPILED.computeIfAbsent(glob, PartyRules::globToRegex).matcher(name).matches()) return true;
        }
        return false;
    }

    private static void checkSize(int size, String what) {
        if (size < 2 || size > MAX_PARTY_SIZE) {
            throw new IllegalArgumentException(what + ": party size must be 2.." + MAX_PARTY_SIZE + ", got " + size);
        }
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toLowerCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
