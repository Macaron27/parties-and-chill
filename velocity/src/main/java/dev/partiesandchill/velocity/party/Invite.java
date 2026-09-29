package dev.partiesandchill.velocity.party;

import java.util.Objects;
import java.util.UUID;

/**
 * A pending party invitation.
 *
 * @param target    the invited player
 * @param inviter   the player who sent the invite
 * @param expiresAt epoch millis after which the invite can no longer be accepted
 */
public record Invite(UUID target, UUID inviter, long expiresAt) {

    public Invite {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(inviter, "inviter");
    }

    /** @return {@code true} once {@code now} has reached {@link #expiresAt()} */
    public boolean expired(long now) {
        return now >= expiresAt;
    }
}
