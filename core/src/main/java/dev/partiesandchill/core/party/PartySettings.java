package dev.partiesandchill.core.party;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Tunable party rules, loaded from {@code config.yml}.
 *
 * @param maxSize             maximum number of members, leader included
 * @param inviteTtl           how long an invite stays valid
 * @param disconnectGrace     how long a disconnected member keeps their slot before being dropped
 * @param warpDelay           delay before members follow the leader into a game server
 * @param gameServers         glob patterns ({@code *}, {@code ?}) of servers that trigger auto-warp
 * @param mutedNoticeCooldown minimum delay between two "I am muted" notices from the same player
 */
public record PartySettings(int maxSize, Duration inviteTtl, Duration disconnectGrace, Duration warpDelay,
                            List<String> gameServers, Duration mutedNoticeCooldown) {

    /** Compiled globs; the set of patterns is tiny and fixed by config. */
    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    public PartySettings {
        if (maxSize < 2) throw new IllegalArgumentException("party max-size must be >= 2, got " + maxSize);
        Objects.requireNonNull(inviteTtl, "inviteTtl");
        Objects.requireNonNull(disconnectGrace, "disconnectGrace");
        Objects.requireNonNull(warpDelay, "warpDelay");
        Objects.requireNonNull(mutedNoticeCooldown, "mutedNoticeCooldown");
        gameServers = List.copyOf(gameServers);
    }

    /** Hypixel-like defaults: 8 players, 60 s invites, 5 min grace, 1 s warp delay. */
    public static PartySettings defaults() {
        return new PartySettings(8, Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofSeconds(1),
                List.of("bw-*", "sw-*", "game-*"), Duration.ofSeconds(30));
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
            if (COMPILED.computeIfAbsent(glob, PartySettings::globToRegex).matcher(name).matches()) return true;
        }
        return false;
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
