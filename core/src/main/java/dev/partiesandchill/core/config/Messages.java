package dev.partiesandchill.core.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code messages.yml} rendered with MiniMessage. Keys are dotted paths ({@code invite.received}); YAML lists
 * become multi-line messages. Keys missing from the user's file fall back to the bundled defaults.
 */
public final class Messages {

    /** Characters a Minecraft/Floodgate username can contain; such names are safe to parse (needed inside click args). */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.*\\-]{1,36}");

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, String> templates;
    private final TagResolver globals;

    private Messages(Map<String, String> templates) {
        this.templates = Map.copyOf(templates);
        // Rendered once instead of re-parsed in every message; their styles can't leak into the text after them.
        this.globals = TagResolver.resolver(
                Placeholder.component("prefix", miniMessage.deserialize(templates.getOrDefault("prefix", ""))),
                Placeholder.component("separator", miniMessage.deserialize(templates.getOrDefault("separator", ""))));
    }

    /**
     * Loads {@code messages.yml} from {@code dataDirectory} (writing the default on first start) on top of
     * the bundled defaults.
     *
     * @throws IOException if a file can't be read
     */
    public static Messages load(Path dataDirectory) throws IOException {
        Map<String, String> templates = bundledTemplates();
        Path file = PluginConfig.copyDefault(dataDirectory, "messages.yml");
        flatten("", YamlConfigurationLoader.builder().path(file).build().load(), templates);
        return new Messages(templates);
    }

    /** @return messages built from the bundled {@code messages.yml} only */
    public static Messages defaults() {
        try {
            return new Messages(bundledTemplates());
        } catch (IOException e) {
            throw new IllegalStateException("bundled messages.yml is unreadable", e);
        }
    }

    /** @return every known key */
    public java.util.Set<String> keys() {
        return templates.keySet();
    }

    /**
     * Renders a message.
     *
     * @param key     dotted key, e.g. {@code error.not-leader}
     * @param players placeholder → player name
     * @param values  placeholder → raw text; always inserted verbatim, never parsed
     * @return the component; an unknown key renders as the key itself so typos are visible in-game
     */
    public Component render(String key, Map<String, String> players, Map<String, String> values) {
        return render(key, players, values, Map.of());
    }

    /**
     * Renders a message that also embeds pre-rendered components (e.g. a status icon).
     *
     * @param components placeholder → component inserted as-is
     */
    public Component render(String key, Map<String, String> players, Map<String, String> values,
                            Map<String, Component> components) {
        String template = templates.get(key);
        if (template == null) return Component.text(key);
        List<TagResolver> resolvers = new ArrayList<>();
        resolvers.add(globals);
        // Names are parsed only when they can't contain tags: parsed placeholders also work inside click/hover args.
        players.forEach((tag, name) -> resolvers.add(SAFE_NAME.matcher(name).matches()
                ? Placeholder.parsed(tag, name)
                : Placeholder.unparsed(tag, name)));
        values.forEach((tag, value) -> resolvers.add(Placeholder.unparsed(tag, value)));
        components.forEach((tag, component) -> resolvers.add(Placeholder.component(tag, component)));
        return miniMessage.deserialize(template, TagResolver.resolver(resolvers));
    }

    /** Renders a message whose placeholders are all raw text. */
    public Component render(String key, Map<String, String> values) {
        return render(key, Map.of(), values);
    }

    /** Renders a message without placeholders. */
    public Component render(String key) {
        return render(key, Map.of(), Map.of());
    }

    private static Map<String, String> bundledTemplates() throws IOException {
        Map<String, String> templates = new HashMap<>();
        var url = Objects.requireNonNull(Messages.class.getResource("/messages.yml"), "bundled messages.yml");
        flatten("", YamlConfigurationLoader.builder().url(url).build().load(), templates);
        return templates;
    }

    private static void flatten(String prefix, ConfigurationNode node, Map<String, String> out) {
        if (node.isMap()) {
            node.childrenMap().forEach((k, child) -> flatten(prefix.isEmpty() ? k.toString() : prefix + "." + k, child, out));
        } else if (node.isList()) {
            out.put(prefix, node.childrenList().stream().map(ConfigurationNode::getString)
                    .collect(Collectors.joining("<newline>")));
        } else if (node.getString() != null) {
            out.put(prefix, node.getString());
        }
    }
}
