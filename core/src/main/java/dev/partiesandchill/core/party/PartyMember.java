package dev.partiesandchill.core.party;

import java.util.Objects;
import java.util.UUID;

/**
 * One member of a {@link Party}. Immutable: state changes produce a new instance.
 *
 * @param id       the player's UUID (the only identity the party system tracks)
 * @param joinedAt epoch millis when the player joined the party
 * @param dropAt   epoch millis at which a disconnected member is removed; {@code 0} while online
 * @param role     the member's rank; {@link Party} keeps it in line with {@link Party#leader()}
 */
public record PartyMember(UUID id, long joinedAt, long dropAt, PartyRole role) {

    public PartyMember {
        Objects.requireNonNull(id, "id");
        if (role == null) role = PartyRole.MEMBER; // parties stored by 1.2 have no roles
    }

    /** Creates an online member who joined at {@code now}. */
    public static PartyMember joined(UUID id, long now) {
        return new PartyMember(id, now, 0, PartyRole.MEMBER);
    }

    /** @return {@code true} if the member is currently connected to the network */
    public boolean online() {
        return dropAt == 0;
    }

    /** @return a copy flagged as disconnected, to be dropped at {@code dropAt} */
    public PartyMember disconnected(long dropAt) {
        return new PartyMember(id, joinedAt, dropAt, role);
    }

    /** @return a copy flagged as connected again */
    public PartyMember reconnected() {
        return new PartyMember(id, joinedAt, 0, role);
    }

    /** @return a copy with {@code role} */
    public PartyMember withRole(PartyRole role) {
        return new PartyMember(id, joinedAt, dropAt, role);
    }
}
