package dev.partiesandchill.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.partiesandchill.velocity.chat.PartyChat;
import dev.partiesandchill.velocity.config.Messages;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;

/** {@code /pchat} ({@code /pc}): {@code /pc <message>} sends once, {@code /pc} alone toggles the chat lock. */
public final class PartyChatCommand implements SimpleCommand {

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
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(messages.render("error.players-only"));
            return;
        }
        String[] args = invocation.arguments();
        executor.execute(() -> {
            try {
                if (args.length == 0) chat.toggle(player);
                else chat.send(player, String.join(" ", args), 0);
            } catch (RuntimeException e) {
                logger.error("/pchat failed for {}", player.getUsername(), e);
                player.sendMessage(messages.render("error.unavailable"));
            }
        });
    }
}
