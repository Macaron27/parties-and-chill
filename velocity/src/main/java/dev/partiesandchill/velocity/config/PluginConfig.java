package dev.partiesandchill.velocity.config;

import dev.partiesandchill.velocity.party.PartySettings;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Parsed {@code config.yml}.
 *
 * @param party party rules
 * @param redis multi-proxy synchronisation settings
 */
public record PluginConfig(PartySettings party, Redis redis) {

    /**
     * @param enabled {@code false} runs single-proxy, fully in memory
     * @param uri     {@code redis://[user:password@]host:port/db}
     * @param prefix  key/channel prefix shared by every proxy of the network
     * @param proxyId unique name of this proxy
     */
    public record Redis(boolean enabled, String uri, String prefix, String proxyId) {
    }

    /**
     * Loads {@code config.yml} from {@code dataDirectory}, writing the bundled default first if it is missing.
     *
     * @throws IOException              if the file can't be read or written
     * @throws IllegalArgumentException if a value is invalid (the message names the offending key)
     */
    public static PluginConfig load(Path dataDirectory) throws IOException {
        Path file = copyDefault(dataDirectory, "config.yml");
        ConfigurationNode root = YamlConfigurationLoader.builder().path(file).build().load();

        boolean autoWarp = root.node("auto-warp", "enabled").getBoolean(true);
        PartySettings party = new PartySettings(
                root.node("party", "max-size").getInt(8),
                duration(root, "60s", "party", "invite-timeout"),
                duration(root, "5m", "party", "disconnect-grace"),
                duration(root, "1s", "auto-warp", "delay"),
                autoWarp ? strings(root.node("auto-warp", "servers")) : List.of(),
                duration(root, "30s", "chat", "muted-notice-cooldown"));

        ConfigurationNode redis = root.node("redis");
        String proxyId = redis.node("proxy-id").getString("");
        return new PluginConfig(party, new Redis(
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
