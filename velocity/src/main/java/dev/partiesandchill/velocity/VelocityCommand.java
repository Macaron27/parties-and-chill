package dev.partiesandchill.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.partiesandchill.core.PartiesCore;
import dev.partiesandchill.core.command.ProxyCommand;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A core command registered with Velocity; only players may run it. */
record VelocityCommand(PartiesAndChill plugin, PartiesCore core, ProxyCommand command) implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
        if (invocation.source() instanceof Player player) command.execute(plugin.wrap(player), invocation.arguments());
        else invocation.source().sendMessage(core.messages().render("error.players-only"));
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        return invocation.source() instanceof Player player
                ? command.suggest(plugin.wrap(player), invocation.arguments())
                : CompletableFuture.completedFuture(List.of());
    }
}
