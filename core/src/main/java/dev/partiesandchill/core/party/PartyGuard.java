package dev.partiesandchill.core.party;

import java.util.UUID;

/**
 * Lets backend plugins cancel party actions (the developer API's cancellable events). {@link PartyManager} calls
 * it outside the store lock, on the thread running the action; {@link #allows} may block briefly while a backend
 * answers.
 */
public interface PartyGuard {

    /** What backend plugins can cancel. */
    enum Action { CREATE, JOIN, DISBAND, CHAT }

    /**
     * Cheap and I/O-free, so the manager only prepares a check (extra store reads) when someone may cancel.
     *
     * @return {@code false} if {@link #allows} would return {@code true} anyway
     */
    boolean watches(Action action, UUID player);

    /**
     * @param player  who acts: the leader creating or disbanding, the player joining, the chat sender
     * @param party   the party right before the action; for {@link Action#CREATE}, the party about to be created
     * @param message the chat line for {@link Action#CHAT}, {@code ""} otherwise
     * @return {@code false} if a plugin cancelled the action
     */
    boolean allows(Action action, UUID player, Party party, String message);
}
