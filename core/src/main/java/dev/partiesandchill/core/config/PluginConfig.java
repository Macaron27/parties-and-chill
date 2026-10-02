package dev.partiesandchill.core.config;

import dev.partiesandchill.core.party.PartyRules;
import dev.partiesandchill.core.party.PartyRules.Right;
import dev.partiesandchill.core.party.PartyRules.SizePermission;
import dev.partiesandchill.core.party.PartySettings;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Parsed {@code config.yml}.
 *
 * @param party party rules
 * @param chat  how party chat lines are dressed up on this proxy
 * @param redis multi-proxy synchronisation settings
 */
public record PluginConfig(PartyRules party, Chat chat, Redis redis) {

    /**
     * @param timestamps   formats the hover timestamp of party chat lines (this proxy's time zone)
     * @param mentionSound sound played to {@code @mentioned} players by the backend bridge; empty for none
     * @param volume       mention sound volume
     * @param pitch        mention sound pitch
     */
    public record Chat(DateTimeFormatter timestamps, String mentionSound, float volume, float pitch) {

        /** @return what the bundled {@code config.yml} says */
        public static Chat defaults() {
            return new Chat(DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()),
                    "ENTITY_EXPERIENCE_ORB_PICKUP", 1f, 1f);
        }
    }

    /**
     * @param enabled {@code false} runs single-proxy, fully in memory
     * @param uri     {@code redis://[user:password@]host:port/db}
     * @param prefix  key/channel prefix shared by every proxy of the network
     * @param proxyId unique name of this proxy
     */
    public record Redis(boolean enabled, String uri, String prefix, String proxyId) {
    }

    /** {@code roles.moderator} keys. */
    private static final Map<String, Right> MODERATOR_KEYS = Map.of(
            "invite", Right.INVITE, "kick", Right.KICK, "warp", Right.WARP, "promote", Right.PROMOTE,
            "settings", Right.SETTINGS, "start-games", Right.START_GAMES, "chat-moderation", Right.MODERATE_CHAT);

    /**
     * Loads {@code config.yml} from {@code dataDirectory}, writing the bundled default first if it is missing.
     *
     * @throws IOException              if the file can't be read or written
     * @throws IllegalArgumentException if a value is invalid (the message names the offending key)
     */
    public static PluginConfig load(Path dataDirectory) throws IOException {
        Path file = copyDefault(dataDirectory, "config.yml");
        ConfigurationNode root = YamlConfigurationLoader.builder().path(file).build().load();
        PartyRules builtIn = PartyRules.defaults();

        boolean autoWarp = root.node("auto-warp", "enabled").getBoolean(true);
        PartyRules party;
        try {
            party = new PartyRules(
                    root.node("party", "max-size").getInt(builtIn.maxSize()),
                    duration(root, "60s", "party", "invite-timeout"),
                    duration(root, "5m", "party", "disconnect-grace"),
                    duration(root, "1s", "auto-warp", "delay"),
                    autoWarp ? strings(root.node("auto-warp", "servers")) : List.of(),
                    duration(root, "30s", "chat", "muted-notice-cooldown"),
                    sizePermissions(root.node("party", "permission-sizes")),
                    moderatorRights(root.node("roles", "moderator"), builtIn.moderatorRights()),
                    defaults(root.node("party", "defaults")));
        } catch (IllegalArgumentException e) {
            throw e.getMessage().startsWith("config.yml") ? e : new IllegalArgumentException("config.yml: party." + e.getMessage(), e);
        }

        ConfigurationNode redis = root.node("redis");
        String proxyId = redis.node("proxy-id").getString("");
        return new PluginConfig(party, chat(root.node("chat")), new Redis(
                redis.node("enabled").getBoolean(false),
                redis.node("uri").getString("redis://localhost:6379/0"),
                redis.node("prefix").getString("pnc:"),
                proxyId.isBlank() ? "proxy-" + UUID.randomUUID().toString().substring(0, 8) : proxyId));
    }

    /**
     * Copies a bundled resource into {@code dataDirectory} unless the file already exists.
     *
     * @return the path of the (possibly new) file
     */
    static Path copyDefault(Path dataDirectory, String name) throws IOException {
        Path file = dataDirectory.resolve(name);
        if (Files.notExists(file)) {
            Files.createDirectories(dataDirectory);
            try (InputStream in = PluginConfig.class.getResourceAsStream("/" + name)) {
                if (in == null) throw new IOException("bundled " + name + " is missing from the jar");
                Files.copy(in, file);
            }
        }
        return file;
    }

    /** {@code permission: size} pairs; YAML keys keep their dots ({@code parties.size.16} is one key). */
    private static List<SizePermission> sizePermissions(ConfigurationNode node) {
        List<SizePermission> sizes = new ArrayList<>();
        node.childrenMap().forEach((permission, size) -> {
            String key = "config.yml: party.permission-sizes." + permission;
            if (!(size.raw() instanceof Integer value)) throw new IllegalArgumentException(key + " must be a whole number");
            try {
                sizes.add(new SizePermission(permission.toString(), value));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(key + " — " + e.getMessage(), e);
            }
        });
        return sizes;
    }

    private static Set<Right> moderatorRights(ConfigurationNode node, Set<Right> fallback) {
        Set<Right> rights = EnumSet.noneOf(Right.class);
        MODERATOR_KEYS.forEach((key, right) -> {
            if (node.node(key).getBoolean(fallback.contains(right))) rights.add(right);
        });
        return rights;
    }

    private static PartySettings defaults(ConfigurationNode node) {
        PartySettings builtIn = PartySettings.DEFAULTS;
        return new PartySettings(
                node.node("auto-warp").getBoolean(builtIn.autoWarp()),
                node.node("chat").getBoolean(builtIn.chat()),
                node.node("all-invite").getBoolean(builtIn.allInvite()),
                node.node("all-warp").getBoolean(builtIn.allWarp()),
                node.node("public").getBoolean(builtIn.open()),
                0, false, 0);
    }

    private static Chat chat(ConfigurationNode node) {
        Chat builtIn = Chat.defaults();
        String pattern = node.node("timestamp-format").getString("HH:mm:ss");
        DateTimeFormatter timestamps;
        try {
            timestamps = DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("config.yml: chat.timestamp-format — " + e.getMessage(), e);
        }
        ConfigurationNode mention = node.node("mention");
        return new Chat(timestamps, mention.node("sound").getString(builtIn.mentionSound()).trim(),
                mention.node("volume").getFloat(builtIn.volume()), mention.node("pitch").getFloat(builtIn.pitch()));
    }

    private static java.time.Duration duration(ConfigurationNode root, String fallback, String... path) {
        String raw = root.node((Object[]) path).getString(fallback);
        try {
            return Durations.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("config.yml: " + String.join(".", path) + " — " + e.getMessage(), e);
        }
    }

    private static List<String> strings(ConfigurationNode node) {
        try {
            return node.getList(String.class, List.of());
        } catch (SerializationException e) {
            throw new IllegalArgumentException("config.yml: " + node.path() + " must be a list of server names", e);
        }
    }
}
