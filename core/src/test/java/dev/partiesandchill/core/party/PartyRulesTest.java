package dev.partiesandchill.core.party;

import dev.partiesandchill.core.party.PartyRules.Right;
import dev.partiesandchill.core.party.PartyRules.SizePermission;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartyRulesTest {

    static PartyRules rules(int maxSize, List<String> servers, List<SizePermission> sizes, Set<Right> moderator) {
        return new PartyRules(maxSize, Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofSeconds(1), servers,
                Duration.ofSeconds(30), sizes, moderator, PartySettings.DEFAULTS);
    }

    @Test
    void gameServerGlobs() {
        PartyRules rules = rules(8, List.of("bw-*", "duels-?", "skywars.solo"), List.of(), Set.of());
        assertTrue(rules.isGameServer("bw-12"));
        assertTrue(rules.isGameServer("BW-lobby-less"));
        assertTrue(rules.isGameServer("duels-1"));
        assertFalse(rules.isGameServer("duels-10"));
        assertTrue(rules.isGameServer("skywars.solo"));
        assertFalse(rules.isGameServer("skywarsXsolo"), "dots are literal, not regex wildcards");
        assertFalse(rules.isGameServer("lobby-1"));
    }

    @Test
    void rejectsTinyAndHugeParties() {
        assertThrows(IllegalArgumentException.class, () -> rules(1, List.of(), List.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new SizePermission("parties.size.2000", 2000));
        assertThrows(IllegalArgumentException.class, () -> new SizePermission("parties.size.1", 1));
    }

    @Test
    void theBiggestGrantedSizeWins() {
        PartyRules rules = rules(8, List.of(), List.of(new SizePermission("vip", 12), new SizePermission("mvp++", 24),
                new SizePermission("mvp", 16)), Set.of());
        assertEquals(8, rules.sizeLimit(permission -> false), "no permission: the default");
        assertEquals(12, rules.sizeLimit(Set.of("vip")::contains));
        assertEquals(24, rules.sizeLimit(Set.of("vip", "mvp++", "mvp")::contains), "several: the highest");
        assertEquals(16, rules.sizeLimit(Set.of("mvp", "vip")::contains));
    }

    @Test
    void lookupStopsAtTheFirstGrantedSizeAndSkipsSizesBelowTheDefault() {
        PartyRules rules = rules(10, List.of(), List.of(new SizePermission("small", 4), new SizePermission("mid", 12),
                new SizePermission("big", 24)), Set.of());
        List<String> asked = new ArrayList<>();
        Predicate<String> has = permission -> {
            asked.add(permission);
            return permission.equals("mid") || permission.equals("small");
        };
        assertEquals(12, rules.sizeLimit(has));
        assertEquals(List.of("big", "mid"), asked, "biggest first, stops on a grant");

        asked.clear();
        assertEquals(10, rules.sizeLimit(permission -> asked.add(permission) && false));
        assertEquals(List.of("big", "mid"), asked, "sizes below max-size can't win: never checked");
    }

    @Test
    void ownersHaveEveryRightModeratorsTheConfiguredOnesMembersNone() {
        PartyRules rules = rules(8, List.of(), List.of(), Set.of(Right.KICK));
        for (Right right : Right.values()) {
            assertTrue(rules.grants(PartyRole.OWNER, right));
            assertEquals(right == Right.KICK, rules.grants(PartyRole.MODERATOR, right));
            assertFalse(rules.grants(PartyRole.MEMBER, right));
        }
    }
}
