package dev.partiesandchill.core.config;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and prints the short durations used in {@code config.yml} ({@code 500ms}, {@code 60s}, {@code 5m}, {@code 1h30m}). */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");

    private Durations() {
    }

    /**
     * @param text e.g. {@code "1m30s"}; a bare number is read as seconds
     * @return the parsed duration
     * @throws IllegalArgumentException on anything else
     */
    public static Duration parse(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.matches("\\d+")) return Duration.ofSeconds(Long.parseLong(value));
        Matcher matcher = PART.matcher(value);
        Duration total = Duration.ZERO;
        int end = 0;
        while (matcher.find() && matcher.start() == end) {
            long amount = Long.parseLong(matcher.group(1));
            total = total.plus(switch (matcher.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            });
            end = matcher.end();
            while (end < value.length() && value.charAt(end) == ' ') end++;
        }
        if (end != value.length() || end == 0) throw new IllegalArgumentException("invalid duration: '" + text + "'");
        return total;
    }

    /**
     * @return a compact form such as {@code 5m}, {@code 1m30s} or {@code 45s} (rounded down to seconds)
     */
    public static String format(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds == 0) return "0s";
        StringBuilder out = new StringBuilder();
        long days = seconds / 86_400, hours = seconds % 86_400 / 3600, minutes = seconds % 3600 / 60, secs = seconds % 60;
        if (days > 0) out.append(days).append('d');
        if (hours > 0) out.append(hours).append('h');
        if (minutes > 0) out.append(minutes).append('m');
        if (secs > 0) out.append(secs).append('s');
        return out.toString();
    }
}
