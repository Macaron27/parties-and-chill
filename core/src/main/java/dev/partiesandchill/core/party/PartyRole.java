package dev.partiesandchill.core.party;

/** A member's rank in their party, highest first. */
public enum PartyRole {
    /** The party leader: every right, and the only one who can disband, demote or hand over the party. */
    OWNER,
    /** Rights set by {@code roles.moderator} in {@code config.yml}. */
    MODERATOR,
    /** Rights granted by the party's own settings ({@code allinvite}, {@code allwarp}). */
    MEMBER;

    /** @return {@code true} if this role ranks strictly above {@code other} */
    public boolean outranks(PartyRole other) {
        return ordinal() < other.ordinal();
    }
}
