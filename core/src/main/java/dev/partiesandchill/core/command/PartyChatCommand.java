package dev.partiesandchill.core.command;

import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.chat.PartyChat;
import dev.partiesandchill.core.config.Messages;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * {@code /pchat} ({@code /pc}, {@code /party-chat}): {@code /pc <message>} sends once, {@code /pc toggle} (or
 * {@code /pc} alone) toggles the chat lock, {@code /pc spy} lets staff read every party's chat. Without the spy
 * permission, {@code /pc spy} is an ordinary message.
 */
public final class PartyChatCommand implements ProxyCommand {

    private final PartyChat chat;
    private final Messages messages;
    private final ExecutorService executor;
    private final Logger logger;

    public PartyChatCommand(PartyChat chat, Messages messages, ExecutorService executor, Logger logger) {
        this.chat = chat;
        this.messages = messages;
        this.executor = executor;
        this.logger = logger;
    }

    @Override
    public void execute(ProxyPlayer player, String[] args) {
        executor.execute(() -> {
            try {
                String word = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
                if (args.length == 0 || word.equals("toggle")) chat.toggle(player);
                else if (word.equals("spy") && player.hasPermission(PartyChat.SPY_PERMISSION)) chat.toggleSpy(player);
                else chat.send(player, String.join(" ", args), 0);
            } catch (RuntimeException e) {
                logger.error("/pchat failed for {}", player.name(), e);
                player.sendMessage(messages.render("error.unavailable"));
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> suggest(ProxyPlayer player, String[] args) {
        if (args.length > 1) return CompletableFuture.completedFuture(List.of());
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>(List.of("toggle"));
        if (player.hasPermission(PartyChat.SPY_PERMISSION)) options.add("spy");
        return CompletableFuture.completedFuture(options.stream().filter(o -> o.startsWith(typed)).toList());
    }
}
