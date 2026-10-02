package dev.partiesandchill.bungee;

import dev.partiesandchill.core.PartiesCore;
import dev.partiesandchill.core.command.ProxyCommand;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** A core command registered with BungeeCord; only players may run it. */
final class BungeeCommand extends Command implements TabExecutor {

    /** BungeeCord completes on the network thread: with Redis lagging or down, suggest nothing rather than stall it. */
    private static final long SUGGEST_TIMEOUT_MILLIS = 100;

    private final PartiesCore core;
    private final ProxyCommand command;

    BungeeCommand(String name, PartiesCore core, ProxyCommand command, String... aliases) {
        super(name, null, aliases);
        this.core = core;
        this.command = command;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (sender instanceof ProxiedPlayer player) command.execute(new BungeePlayer(player), args);
        else sender.sendMessage(BungeePlayer.bungee(core.messages().render("error.players-only")));
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) return List.of();
        return command.suggest(new BungeePlayer(player), args)
                .completeOnTimeout(List.of(), SUGGEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .exceptionally(error -> List.of())
                .join();
    }
}
