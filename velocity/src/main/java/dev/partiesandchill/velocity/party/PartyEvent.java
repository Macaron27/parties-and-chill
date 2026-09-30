package dev.partiesandchill.velocity.party;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Side effects produced by {@link PartyManager}. They are plain data so they can be fanned out to every
 * proxy through Redis; each proxy then applies them to the players it hosts.
 */
public sealed interface PartyEvent {

    /**
     * A chat message to render from {@code messages.yml}.
     *
     * @param recipients players who receive it
     * @param key        message key, e.g. {@code invite.received}
     * @param players    placeholder name → player UUID (rendered as the player's name)
     * @param values     placeholder name → raw text (never parsed as MiniMessage)
     */
    record Notice(Set<UUID> recipients, String key, Map<String, UUID> players, Map<String, String> values)
            implements PartyEvent {
        public Notice {
            recipients = Set.copyOf(recipients);
            players = Map.copyOf(players);
            values = Map.copyOf(values);
        }
    }

    /**
     * Sends members to a server.
     *
     * @param members     players to move (those already there are skipped by the dispatcher)
     * @param server      target server name
     * @param delayMillis delay before connecting, to avoid hammering the backend
     */
    record Warp(Set<UUID> members, String server, long delayMillis) implements PartyEvent {
        public Warp {
            members = Set.copyOf(members);
        }
    }

    /**
     * Membership changed; backend bridges need a fresh snapshot for these players.
     *
     * @param affected players whose party view changed
     * @param party    the party now, or {@code null} if it was disbanded (or they left it)
     * @param previous when they left: the party as it was before (for backend leave events), otherwise {@code null}.
     *                 Proxies of an older version ignore the field and see {@code null}.
     */
    record PartyChanged(Set<UUID> affected, Party party, Party previous) implements PartyEvent {
        public PartyChanged {
            affected = Set.copyOf(affected);
        }
    }
}
