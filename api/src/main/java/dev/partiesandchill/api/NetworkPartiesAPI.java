package dev.partiesandchill.api;

import dev.partiesandchill.api.event.PartyCreateEvent;
import dev.partiesandchill.api.event.PartyDisbandEvent;
import dev.partiesandchill.api.event.PartyJoinEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Parties &amp; Chill for backend plugins (Spigot/Paper 1.8.8 → 26.x). Get it with {@link NetworkParties#getAPI()}
 * or {@code Bukkit.getServicesManager().load(NetworkPartiesAPI.class)}.
 *
 * <p>Parties live on the Velocity proxy (shared by every proxy through Redis), so the asynchronous methods ask the
 * proxy over a plugin channel. That needs at least one player online on this server to carry the message.
 * Their futures complete <b>on the server main thread</b>: callbacks may use the Bukkit API directly. Never
 * {@code join()} or {@code get()} them on the main thread, since the answer is processed there.
 *
 * <p>A future can complete exceptionally. The cause (thrown by {@code get()} inside an
 * {@code ExecutionException}, or wrapped in a {@code CompletionException} for {@code exceptionally}) is:
 * <ul>
 *   <li>{@link IllegalStateException} when no player is online on this server, or the proxy failed to process
 *       the request (e.g. Redis is down);</li>
 *   <li>{@link java.util.concurrent.TimeoutException} when the proxy doesn't answer within 5 seconds (e.g. it runs
 *       an older Parties &amp; Chill);</li>
 *   <li>{@link java.util.concurrent.CancellationException} when the plugin is disabled before the answer arrives.</li>
 * </ul>
 *
 * <p>Changes follow the same rules as {@code /party} (size limit, leader-only actions), fire the same
 * {@linkplain dev.partiesandchill.api.event events} and show the same chat messages to the players involved.
 * With several proxies (Redis), a cancellable event is only fired if the player it is about is on this server or
 * on the same proxy as the player who carries the request.
 */
public interface NetworkPartiesAPI {

    /**
     * Looks up a player's party anywhere on the network. The player may be on another server, or offline but still
     * within the disconnect grace period.
     *
     * @param playerUuid the player
     * @return the party, or empty if the player is not in one
     * @throws NullPointerException if {@code playerUuid} is null
     */
    CompletableFuture<Optional<Party>> getParty(UUID playerUuid);

    /**
     * Creates a party led by {@code leaderUuid} alone. A one-member party is disbanded the next time it changes
     * while no invite is pending (for example when its leader disconnects), like any party left with one member.
     *
     * @param leaderUuid the future leader; must be online on the network
     * @return {@code true} if the party was created; {@code false} if the player is offline, already in a party,
     * or a {@link PartyCreateEvent} was cancelled
     * @throws NullPointerException if {@code leaderUuid} is null
     */
    CompletableFuture<Boolean> createParty(UUID leaderUuid);

    /**
     * Puts {@code targetUuid} in the party led by {@code leaderUuid} right away, without an invite. Any pending
     * invite from that party to the target is consumed.
     *
     * @param leaderUuid the party leader
     * @param targetUuid the player to add; must be online on the network
     * @return {@code true} if the target joined; {@code false} if {@code leaderUuid} doesn't lead a party, the
     * target is offline or already in a party, the party is full, or a {@link PartyJoinEvent} was cancelled
     * @throws NullPointerException if an argument is null
     */
    CompletableFuture<Boolean> addMember(UUID leaderUuid, UUID targetUuid);

    /**
     * Removes {@code targetUuid} from the party led by {@code leaderUuid}, exactly like {@code /party kick}.
     *
     * @param leaderUuid the party leader
     * @param targetUuid the member to remove
     * @return {@code true} if the member was removed; {@code false} if {@code leaderUuid} doesn't lead a party,
     * {@code targetUuid} is not in it, or both are the same player (a leader can't kick themselves)
     * @throws NullPointerException if an argument is null
     */
    CompletableFuture<Boolean> removeMember(UUID leaderUuid, UUID targetUuid);

    /**
     * Disbands the party led by {@code leaderUuid}, exactly like {@code /party disband}.
     *
     * @param leaderUuid the party leader
     * @return {@code true} if the party was disbanded; {@code false} if the player doesn't lead a party or a
     * {@link PartyDisbandEvent} was cancelled
     * @throws NullPointerException if {@code leaderUuid} is null
     */
    CompletableFuture<Boolean> disbandParty(UUID leaderUuid);

    /**
     * Checks the local cache, without a network round trip. The cache only knows players connected to
     * <b>this</b> server (the proxy pushes their party when they join and whenever it changes); for anyone else it
     * answers {@code false}, so use {@link #getParty(UUID)} for them.
     *
     * @param playerUuid a player connected to this server
     * @return {@code true} if the player is in a party
     */
    boolean isInParty(UUID playerUuid);

    /**
     * Checks the local cache, without a network round trip. Same scope as {@link #isInParty(UUID)}: players
     * connected to this server only.
     *
     * @param playerUuid a player connected to this server
     * @return {@code true} if the player leads a party
     */
    boolean isPartyLeader(UUID playerUuid);
}
