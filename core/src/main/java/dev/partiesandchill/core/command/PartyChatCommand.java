package dev.partiesandchill.core.command;

import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.chat.PartyChat;
import dev.partiesandchill.core.config.Messages;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;

/** {@code /pchat} ({@code /pc}): {@code /pc <message>} sends once, {@code /pc} alone toggles the chat lock. */
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
                if (args.length == 0) chat.toggle(player);
                else chat.send(player, String.join(" ", args), 0);
            } catch (RuntimeException e) {
                logger.error("/pchat failed for {}", player.name(), e);
                player.sendMessage(messages.render("error.unavailable"));
            }
        });
    }
}
