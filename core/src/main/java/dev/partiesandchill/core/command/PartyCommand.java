package dev.partiesandchill.core.command;

import dev.partiesandchill.core.ProxyPlatform;
import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.config.Messages;
import dev.partiesandchill.core.network.Network;
import dev.partiesandchill.core.party.Party;
import dev.partiesandchill.core.party.PartyManager;
import dev.partiesandchill.core.party.PartyManager.Outcome;
import dev.partiesandchill.core.party.PartyMember;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * {@code /party} ({@code /p}): invite, accept, deny, leave, list, kick, promote, disband, warp.
 * {@code /p <player>} is a shortcut for {@code /p invite <player>}. Work runs on virtual threads because
 * with Redis every action is network I/O.
 */
public final class PartyCommand implements ProxyCommand {

    private static final List<String> SUBCOMMANDS =
            List.of("invite", "accept", "deny", "list", "leave", "warp", "kick", "promote", "disband", "help");

    private final ProxyPlatform platform;
    private final PartyManager manager;
    private final Network network;
    private final Messages messages;
    private final ExecutorService executor;
    private final Logger logger;

    public PartyCommand(ProxyPlatform platform, PartyManager manager, Network network, Messages messages,
                        ExecutorService executor, Logger logger) {
        this.platform = platform;
        this.manager = manager;
        this.network = network;
        this.messages = messages;
        this.executor = executor;
        this.logger = logger;
    }

    @Override
    public void execute(ProxyPlayer player, String[] args) {
        executor.execute(() -> {
            try {
                Outcome outcome = handle(player, args);
                if (outcome != Outcome.SUCCESS && outcome != Outcome.CANCELLED) player.sendMessage(messages.render(outcome.messageKey()));
            } catch (RuntimeException e) {
                logger.error("/party {} failed for {}", String.join(" ", args), player.name(), e);
                player.sendMessage(messages.render("error.unavailable"));
            }
        });
    }

    /** @return the manager's verdict; {@link Outcome#SUCCESS} when the reply was already sent */
    private Outcome handle(ProxyPlayer player, String[] args) {
        UUID self = player.uniqueId();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "help" -> reply(player, messages.render("help"));
            case "list" -> list(player);
            case "leave" -> manager.leave(self);
            case "disband" -> manager.disband(self);
            case "warp" -> player.backend()
                    .map(server -> manager.warp(self, server.name()))
                    .orElse(Outcome.SUCCESS);
            case "invite", "accept", "deny", "kick", "promote" -> args.length < 2
                    ? reply(player, messages.render("error.usage", Map.of("usage", "/party " + sub + " <player>")))
                    : targeted(player, sub, args[1]);
            default -> args.length == 1 ? targeted(player, "invite", args[0]) : reply(player, messages.render("help"));
        };
    }

    private Outcome targeted(ProxyPlayer player, String action, String name) {
        UUID self = player.uniqueId();
        return switch (action) {
            case "invite" -> network.findOnline(name)
                    .map(target -> manager.invite(self, target))
                    .orElseGet(() -> notFound(player, name));
            case "accept" -> network.uuidOf(name).map(inviter -> manager.accept(self, inviter)).orElse(Outcome.NO_INVITE);
            case "deny" -> network.uuidOf(name).map(inviter -> manager.deny(self, inviter)).orElse(Outcome.NO_INVITE);
            case "kick" -> memberNamed(self, name).map(target -> manager.kick(self, target)).orElseGet(() -> notMember(self));
            case "promote" -> memberNamed(self, name).map(target -> manager.promote(self, target)).orElseGet(() -> notMember(self));
            default -> throw new IllegalArgumentException(action);
        };
    }

    private Outcome list(ProxyPlayer player) {
        Party party = manager.partyOf(player.uniqueId()).orElse(null);
        if (party == null) return Outcome.NOT_IN_PARTY;
        List<Component> lines = new ArrayList<>();
        lines.add(messages.render("list.header", Map.of("count", Integer.toString(party.size()),
                "max", Integer.toString(manager.settings().maxSize()))));
        party.member(party.leader()).ifPresent(leader -> lines.add(row("list.leader", leader)));
        party.members().stream().filter(m -> !party.isLeader(m.id())).forEach(m -> lines.add(row("list.member", m)));
        lines.add(messages.render("list.footer"));
        return reply(player, Component.join(JoinConfiguration.newlines(), lines));
    }

    private Component row(String key, PartyMember member) {
        Component status = messages.render(member.online() ? "status.online" : "status.offline");
        return messages.render(key, Map.of("player", network.nameOf(member.id()).orElse("?")), Map.of(),
                Map.of("status", status));
    }

    /** Resolves {@code name} among the actor's party members (works for offline members too). */
    private Optional<UUID> memberNamed(UUID self, String name) {
        return manager.partyOf(self).flatMap(party -> party.memberIds().stream()
                .filter(id -> network.nameOf(id).filter(name::equalsIgnoreCase).isPresent())
                .findFirst());
    }

    private Outcome notMember(UUID self) {
        return manager.partyOf(self).isPresent() ? Outcome.TARGET_NOT_IN_PARTY : Outcome.NOT_IN_PARTY;
    }

    private Outcome notFound(ProxyPlayer player, String name) {
        return reply(player, messages.render("error.player-not-found", Map.of("player", name), Map.of()));
    }

    private static Outcome reply(ProxyPlayer player, Component message) {
        player.sendMessage(message);
        return Outcome.SUCCESS;
    }

    @Override
    public CompletableFuture<List<String>> suggest(ProxyPlayer player, String[] args) {
        if (args.length > 2) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            if (args.length <= 1) {
                options.addAll(SUBCOMMANDS);
                options.addAll(onlineNames(player));
            } else {
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "invite", "accept", "deny" -> options.addAll(onlineNames(player));
                    case "kick", "promote" -> manager.partyOf(player.uniqueId()).ifPresent(party ->
                            party.memberIds().stream().filter(id -> !id.equals(player.uniqueId()))
                                    .forEach(id -> network.nameOf(id).ifPresent(options::add)));
                    default -> { }
                }
            }
            return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(typed)).distinct().toList();
        }, executor);
    }

    // ponytail: suggests players on this proxy only; typing a name hosted by another proxy still works.
    private List<String> onlineNames(ProxyPlayer self) {
        return platform.playerNames().stream().filter(name -> !name.equals(self.name())).toList();
    }
}
