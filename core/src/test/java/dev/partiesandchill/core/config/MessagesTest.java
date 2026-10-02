package dev.partiesandchill.core.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    static final Map<String, String> PLAYERS = Map.of("player", "Alice", "target", "Bob", "leader", "Carol");
    static final Map<String, String> VALUES = Map.of("time", "60s", "server", "bw-1", "count", "3",
            "message", "hi", "max", "8", "usage", "/p invite <player>");
    static final Map<String, Component> COMPONENTS = Map.of("status", Component.text("*"));

    final Messages messages = Messages.defaults();

    @Test
    void everyBundledMessageRendersWithoutLeftoverTags() {
        for (String key : messages.keys()) {
            String plain = plain(messages.render(key, PLAYERS, VALUES, COMPONENTS));
            String withoutUsage = plain.replace("/p invite <player>", "");
            assertFalse(withoutUsage.matches("(?s).*<[a-z#/!][^>]*>.*"), () -> key + " has an unknown tag: " + plain);
        }
    }

    @Test
    void everyKeyUsedInCodeExists() throws IOException {
        Pattern literal = Pattern.compile("\"((?:error|invite|party|member|leader|disband|warp|chat|list|status)\\.[a-z.-]+|help)\"");
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = literal.matcher(Files.readString(source));
                while (matcher.find()) if (!messages.keys().contains(matcher.group(1))) missing.add(matcher.group(1));
            }
        }
        assertEquals(Set.of(), missing, "keys referenced in code but absent from messages.yml");
    }

    @Test
    void chatTextIsNeverParsed() {
        String evil = "<red>boom<click:run_command:'/op me'>x</click>";
        Component chat = messages.render("chat.format", Map.of("player", "<bold>Mallory"), Map.of("message", evil));
        String plain = plain(chat);
        assertTrue(plain.contains(evil), plain);
        assertTrue(plain.contains("<bold>Mallory"), "names with tag characters are escaped");
        assertTrue(clicks(chat).isEmpty());
    }

    @Test
    void inviteButtonsRunCommandsWithTheInviterName() {
        Component invite = messages.render("invite.received", Map.of("player", "Alice_01"), Map.of("time", "60s"));
        assertEquals(Set.of("/party accept Alice_01", "/party deny Alice_01"), clicks(invite));
    }

    @Test
    void userFileOverridesAndFallsBackToDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("messages.yml"), """
                prefix: "[P] "
                error:
                  not-leader: "<prefix>leaders only"
                """);
        Messages custom = Messages.load(dir);
        assertEquals("[P] leaders only", plain(custom.render("error.not-leader")));
        assertTrue(plain(custom.render("error.party-full")).startsWith("[P] "), "missing key uses default template + custom prefix");
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Set<String> clicks(Component component) {
        Set<String> commands = new TreeSet<>();
        StreamSupport.stream(component.iterable(ComponentIteratorType.DEPTH_FIRST).spliterator(), false)
                .map(Component::clickEvent).filter(Objects::nonNull)
                .filter(e -> e.action() == ClickEvent.Action.RUN_COMMAND)
                .forEach(e -> commands.add(((ClickEvent.Payload.Text) e.payload()).value()));
        return commands;
    }
}
