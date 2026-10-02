package dev.partiesandchill.bungee;

import dev.partiesandchill.core.config.Messages;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BungeePlayerTest {

    final Messages messages = Messages.defaults();

    @Test
    void clickableInvitesSurviveTheConversion() {
        Component invite = messages.render("invite.received", Map.of("player", "Alice"), Map.of("time", "60s"));
        BaseComponent[] converted = BungeePlayer.bungee(invite);

        String text = TextComponent.toPlainText(converted);
        assertTrue(text.contains("Alice invited you to their party!"), text);
        assertTrue(text.contains("You have 60s to accept. [ACCEPT] [DENY]"), text);

        List<BaseComponent> all = flatten(converted);
        BaseComponent accept = all.stream().filter(c -> c.getClickEvent() != null
                && c.getClickEvent().getValue().equals("/party accept Alice")).findFirst().orElseThrow();
        assertEquals(ClickEvent.Action.RUN_COMMAND, accept.getClickEvent().getAction());
        assertEquals(HoverEvent.Action.SHOW_TEXT, accept.getHoverEvent().getAction());
        assertTrue(all.stream().anyMatch(c -> c.getClickEvent() != null
                && c.getClickEvent().getValue().equals("/party deny Alice")));
    }

    @Test
    void chatTextIsNeverParsed() {
        Component line = messages.render("chat.format", Map.of("player", "Bob"), Map.of("message", "<red>hi §cthere"));
        assertTrue(TextComponent.toPlainText(BungeePlayer.bungee(line)).contains("<red>hi §cthere"));
    }

    private static List<BaseComponent> flatten(BaseComponent[] components) {
        List<BaseComponent> out = new ArrayList<>();
        for (BaseComponent component : components) {
            out.add(component);
            if (component.getExtra() != null) out.addAll(flatten(component.getExtra().toArray(BaseComponent[]::new)));
        }
        return out;
    }
}
