package dev.partiesandchill.core.command;

import dev.partiesandchill.core.ProxyPlatform;
import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.chat.PartyChat;
import dev.partiesandchill.core.config.Durations;
import dev.partiesandchill.core.config.Messages;
import dev.partiesandchill.core.network.Network;
import dev.partiesandchill.core.party.Party;
import dev.partiesandchill.core.party.PartyManager;
import dev.partiesandchill.core.party.PartyManager.Outcome;
import dev.partiesandchill.core.party.PartyMember;
import dev.partiesandchill.core.party.PartyRole;
import dev.partiesandchill.core.party.PartySettings;
import dev.partiesandchill.core.party.PartySettings.Toggle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * {@code /party} ({@code /p}): invite, accept, deny, join, leave, list, kick, promote, demote, disband, warp,
 * settings, chat. {@code /p <player>} is a shortcut for {@code /p invite <player>}. Work runs on virtual threads
 * because with Redis every action is network I/O.
 */
public final class PartyCommand implements ProxyCommand {

    private static final List<String> SUBCOMMANDS = List.of("invite", "accept", "deny", "join", "list", "leave", "warp",
            "kick", "promote", "demote", "disband", "settings", "chat", "help");
    private static final String SETTINGS_USAGE =
            "/party settings [autowarp|chat|allinvite|allwarp|public|mute|maxsize <size|max>|slowmode <time|off>]";
    private static final List<String> SETTING_KEYS =
            List.of("autowarp", "chat", "allinvite", "allwarp", "public", "mute", "maxsize", "slowmode");

    private final ProxyPlatform platform;
    private final PartyManager manager;
    private final Network network;
    private final Messages messages;
    private final PartyChat chat;
    private final ExecutorService executor;
    private final Logger logger;

    public PartyCommand(ProxyPlatform platform, PartyManager manager, Network network, Messages messages, PartyChat chat,
                        ExecutorService executor, Logger logger) {
        this.platform = platform;
        this.manager = manager;
        this.network = network;
        this.messages = messages;
        this.chat = chat;
        this.executor = executor;
        this.logger = logger;
    }

    @Override
    public void execute(ProxyPlayer player, String[] args) {
        executor.execute(() -> {
            try {
                Outcome outcome = handle(player, args);
                if (!outcome.messageKey().isEmpty()) player.sendMessage(messages.render(outcome.messageKey()));
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
            case "settings" -> settings(player, args);
            case "chat" -> {
                if (args.length == 1) chat.toggle(player);
                else chat.send(player, String.join(" ", Arrays.copyOfRange(args, 1, args.length)), 0);
                yield Outcome.SUCCESS;
            }
            case "invite", "accept", "deny", "join", "kick", "promote", "demote" -> args.length < 2
                    ? usage(player, "/party " + sub + " <player>")
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
            case "join" -> network.uuidOf(name).map(member -> manager.join(self, member)).orElse(Outcome.NOT_PUBLIC);
            case "kick" -> memberNamed(self, name).map(target -> manager.kick(self, target)).orElseGet(() -> notMember(self));
            case "promote" -> memberNamed(self, name).map(target -> manager.promote(self, target)).orElseGet(() -> notMember(self));
            case "demote" -> memberNamed(self, name).map(target -> manager.demote(self, target)).orElseGet(() -> notMember(self));
            default -> throw new IllegalArgumentException(action);
        };
    }

    /** {@code /p settings} shows the menu; {@code /p settings <key> [value]} changes one setting. */
    private Outcome settings(ProxyPlayer player, String[] args) {
        UUID self = player.uniqueId();
        if (args.length == 1) return menu(player);
        String key = args[1].toLowerCase(Locale.ROOT);
        Optional<Toggle> toggle = Toggle.byKey(key);
        if (toggle.isPresent()) return manager.toggle(self, toggle.get());
        if (args.length < 3 || !key.equals("maxsize") && !key.equals("slowmode")) return usage(player, SETTINGS_USAGE);
        String value = args[2].toLowerCase(Locale.ROOT);
        try {
            if (key.equals("maxsize")) return manager.setMaxSize(self, value.equals("max") ? 0 : Integer.parseInt(value));
            long seconds = value.equals("off") ? 0 : Durations.parse(value).toSeconds();
            return manager.setSlowMode(self, (int) Math.min(seconds, Integer.MAX_VALUE));
        } catch (IllegalArgumentException e) { // NumberFormatException included
            return usage(player, SETTINGS_USAGE);
        }
    }

    private Outcome menu(ProxyPlayer player) {
        Party party = manager.partyOf(player.uniqueId()).orElse(null);
        if (party == null) return Outcome.NOT_IN_PARTY;
        PartySettings settings = party.settings();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.render("settings.header"));
        for (Toggle toggle : Toggle.values()) {
            Component state = messages.render(settings.get(toggle) ? "settings.state-on" : "settings.state-off");
            // Setting keys are plain words: passed like names so they also work inside click arguments.
            lines.add(messages.render("settings.toggle", Map.of("key", toggle.key()), Map.of(),
                    Map.of("name", messages.render("settings.name." + toggle.key()), "state", state)));
        }
        int limit = manager.sizeLimit(party); // read once: with Redis it may be a round trip
        lines.add(messages.render("settings.max-size-line",
                Map.of("max", Integer.toString(settings.cappedAt(limit)), "limit", Integer.toString(limit))));
        Component slow = settings.slowModeSeconds() == 0 ? messages.render("settings.slow-mode-none")
                : Component.text(Durations.format(Duration.ofSeconds(settings.slowModeSeconds())));
        lines.add(messages.render("settings.slow-mode-line", Map.of(), Map.of(), Map.of("time", slow)));
        lines.add(messages.render("settings.footer"));
        return reply(player, Component.join(JoinConfiguration.newlines(), lines));
    }

    private Outcome list(ProxyPlayer player) {
        Party party = manager.partyOf(player.uniqueId()).orElse(null);
        if (party == null) return Outcome.NOT_IN_PARTY;
        List<Component> lines = new ArrayList<>();
        lines.add(messages.render("list.header", Map.of("count", Integer.toString(party.size()),
                "max", Integer.toString(manager.maxSize(party)))));
        for (PartyRole role : PartyRole.values()) {
            String key = switch (role) {
                case OWNER -> "list.leader";
                case MODERATOR -> "list.moderator";
                case MEMBER -> "list.member";
            };
            party.members().stream().filter(m -> m.role() == role).forEach(m -> lines.add(row(key, m)));
        }
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

    private Outcome usage(ProxyPlayer player, String usage) {
        return reply(player, messages.render("error.usage", Map.of("usage", usage)));
    }

    private static Outcome reply(ProxyPlayer player, Component message) {
        player.sendMessage(message);
        return Outcome.SUCCESS;
    }

    @Override
    public CompletableFuture<List<String>> suggest(ProxyPlayer player, String[] args) {
        if (args.length > 3) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            if (args.length <= 1) {
                options.addAll(SUBCOMMANDS);
                options.addAll(onlineNames(player));
            } else if (args.length == 3) {
                if (sub.equals("settings") && args[1].equalsIgnoreCase("maxsize")) options.add("max");
                if (sub.equals("settings") && args[1].equalsIgnoreCase("slowmode")) options.addAll(List.of("off", "5s", "30s"));
            } else {
                switch (sub) {
                    case "invite", "accept", "deny", "join" -> options.addAll(onlineNames(player));
                    case "kick", "promote", "demote" -> manager.partyOf(player.uniqueId()).ifPresent(party ->
                            party.memberIds().stream().filter(id -> !id.equals(player.uniqueId()))
                                    .forEach(id -> network.nameOf(id).ifPresent(options::add)));
                    case "settings" -> options.addAll(SETTING_KEYS);
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
