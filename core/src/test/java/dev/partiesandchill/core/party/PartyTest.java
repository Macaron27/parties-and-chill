package dev.partiesandchill.core.party;

import dev.partiesandchill.core.party.PartySettings.Toggle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartyTest {

    static final UUID ALICE = UUID.randomUUID(), BOB = UUID.randomUUID(), CAROL = UUID.randomUUID();

    @Test
    void theLeaderIsAlwaysTheOnlyOwner() {
        Party party = Party.create(ALICE, 0, PartySettings.DEFAULTS)
                .withMember(PartyMember.joined(BOB, 1))
                .withMember(new PartyMember(CAROL, 2, 0, PartyRole.OWNER)); // a forged second owner
        assertEquals(PartyRole.OWNER, party.role(ALICE).orElseThrow());
        assertEquals(PartyRole.MEMBER, party.role(BOB).orElseThrow());
        assertEquals(PartyRole.MODERATOR, party.role(CAROL).orElseThrow(), "only the leader can be owner");

        Party handedOver = party.withLeader(BOB);
        assertEquals(PartyRole.OWNER, handedOver.role(BOB).orElseThrow());
        assertEquals(PartyRole.MODERATOR, handedOver.role(ALICE).orElseThrow(), "the old owner stays as moderator");
        assertTrue(party.role(UUID.randomUUID()).isEmpty());
    }

    @Test
    void partiesStoredBy12GetDefaults() {
        // What Gson builds from 1.2 JSON: no role on members, no settings on the party.
        Party old = new Party(UUID.randomUUID(), ALICE,
                List.of(new PartyMember(ALICE, 0, 0, null), new PartyMember(BOB, 0, 0, null)), List.of(), null);
        assertEquals(PartySettings.DEFAULTS, old.settings());
        assertEquals(PartyRole.OWNER, old.role(ALICE).orElseThrow());
        assertEquals(PartyRole.MEMBER, old.role(BOB).orElseThrow());
    }

    @Test
    void settingsToggleOneSwitchAtATime() {
        PartySettings settings = PartySettings.DEFAULTS;
        for (Toggle toggle : Toggle.values()) {
            PartySettings flipped = settings.with(toggle, !settings.get(toggle));
            for (Toggle other : Toggle.values()) {
                assertEquals(other == toggle ? !settings.get(other) : settings.get(other), flipped.get(other), toggle + "/" + other);
            }
            assertEquals(toggle, Toggle.byKey(toggle.key().toUpperCase()).orElseThrow());
        }
        assertFalse(Toggle.byKey("maxsize").isPresent(), "numbers aren't toggles");
        assertThrows(IllegalArgumentException.class, () -> settings.withSlowMode(PartySettings.MAX_SLOW_MODE_SECONDS + 1));
        assertThrows(IllegalArgumentException.class, () -> settings.withMaxSize(-1));
    }

    @Test
    void outranks() {
        assertTrue(PartyRole.OWNER.outranks(PartyRole.MODERATOR));
        assertTrue(PartyRole.MODERATOR.outranks(PartyRole.MEMBER));
        assertFalse(PartyRole.MODERATOR.outranks(PartyRole.MODERATOR));
        assertFalse(PartyRole.MEMBER.outranks(PartyRole.OWNER));
    }
}
