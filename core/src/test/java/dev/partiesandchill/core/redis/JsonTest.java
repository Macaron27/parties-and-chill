package dev.partiesandchill.core.redis;

import dev.partiesandchill.core.party.Party;
import dev.partiesandchill.core.party.PartyEvent;
import dev.partiesandchill.core.party.PartyMember;
import dev.partiesandchill.core.party.PartyRole;
import dev.partiesandchill.core.party.PartySettings;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {

    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void partiesWrittenBy12LoadWithDefaults() {
        String stored = """
                {"id":"00000000-0000-0000-0000-000000000001","leader":"%s",\
                "members":[{"id":"%s","joinedAt":1,"dropAt":0},{"id":"%s","joinedAt":2,"dropAt":0}],"invites":[]}"""
                .formatted(ALICE, ALICE, BOB);
        Party party = Json.party(stored);
        assertEquals(PartySettings.DEFAULTS, party.settings());
        assertEquals(PartyRole.OWNER, party.role(ALICE).orElseThrow());
        assertEquals(PartyRole.MEMBER, party.role(BOB).orElseThrow());
    }

    @Test
    void rolesAndSettingsRoundTrip() {
        Party party = Party.create(ALICE, 1, PartySettings.DEFAULTS.withSlowMode(5).withMaxSize(3))
                .withMember(PartyMember.joined(BOB, 2)).withRole(BOB, PartyRole.MODERATOR);
        assertEquals(party, Json.party(Json.party(party)));
    }

    @Test
    void chatEventsRoundTrip() {
        PartyEvent chat = new PartyEvent.Chat(Set.of(ALICE, BOB), BOB, ALICE, "@Alice <b>hi", Set.of(ALICE), 42);
        assertEquals(chat, Json.event(Json.event(chat)));
    }
}
