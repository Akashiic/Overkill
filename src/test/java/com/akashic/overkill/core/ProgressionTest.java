package com.akashic.overkill.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ProgressionTest {

    @Test
    void theCurveIsTheRods() {
        Leveling l = Leveling.DEFAULT;
        assertEquals(21, l.xpToNext(1));
        assertEquals(331, l.totalXpFor(10));
        assertEquals(54_732, l.totalXpFor(99));
        assertEquals(1, l.levelOf(20));
        assertEquals(2, l.levelOf(21));
        assertEquals(99, l.levelOf(Long.MAX_VALUE / 4));
        assertEquals(0, l.xpToNext(99));
        assertEquals(5, l.xpIntoLevel(26));
    }

    @Test
    void killsGiveXpAndProudSoul() {
        assertEquals(10, Leveling.xpForKill(20, 0), "a zombie");
        assertEquals(16, Leveling.xpForKill(20, 5.99f), "at SSS");
        assertEquals(1, Leveling.xpForKill(1, 0), "at least one");
        assertEquals(Leveling.MAX_KILL_XP, Leveling.xpForKill(10_000, 0), "capped");
        assertEquals(5, Leveling.proudSoulForKill(5, 0));
        assertEquals(7, Leveling.proudSoulForKill(5, 5));
        assertEquals(100, Leveling.proudSoulForKill(500, 5), "SlashBlade caps at 100");
    }

    @Test
    void theAmplifierIsSlashBladesRefineCurve() {
        assertEquals(0, Leveling.amplifier(1, 0, 4), 1e-9);
        assertEquals(2, Leveling.amplifier(21, 0, 4), 1e-9);
        assertTrue(Leveling.amplifier(99, 1000, 4) < 4, "never past +100%");
    }

    @Test
    void theStarsWeighFiveToOne() {
        Random r = new Random(7);
        Map<Star, Integer> n = new EnumMap<>(Star.class);
        for (int i = 0; i < 200_000; i++) {
            n.merge(Star.roll(r), 1, Integer::sum);
        }
        assertEquals(0.801, n.get(Star.NORMAL) / 200_000.0, 0.005);
        assertEquals(0.160, n.get(Star.PRATA) / 200_000.0, 0.005);
        assertEquals(0.032, n.get(Star.OURO) / 200_000.0, 0.002);
        assertEquals(0.0064, n.get(Star.IRIDIO) / 200_000.0, 0.001);
        assertSame(Star.IRIDIO, Star.parse("irídio"));
        assertSame(Star.OURO, Star.parse("2"));
        assertNull(Star.parse("bronze"));
    }

    @Test
    void theForgeLadderMatchesTheRods() {
        Forge f = Forge.DEFAULT;
        assertEquals(Forge.ASCENSION, f.max());
        assertEquals(0.5, f.step(5).chance, 1e-9);
        assertEquals(0.003, f.step(17).chance, 1e-9);
        assertEquals(2600, f.expectedTries(0, 17, null, null), 60, "about 2,600 tries without help");
        assertEquals(390, f.expectedTries(0, 17, StoneTier.MYTHIC, SealTier.P100), 20);
        assertEquals(0.95, f.chance(f.step(2), StoneTier.MYTHIC), 1e-9, "capped");
        assertEquals(1.0, f.chance(f.step(1), StoneTier.MYTHIC), 1e-9, "a chance above the cap stays");
        assertEquals(0.10, f.chance(f.step(18), StoneTier.MYTHIC), 1e-9, "no stone at the Ascension");
        assertEquals(0, f.regression(f.step(1), 0, null), 1e-9);
        assertEquals(0.25, f.regression(f.step(5), 4, SealTier.P50), 1e-9);
        assertEquals(1.68, Forge.multiplier(17), 1e-9);
        assertEquals(2.18, Forge.multiplier(18), 1e-9);
        assertEquals("ASC", Forge.label(18));
        assertEquals("+7", Forge.label(7));
    }

    @Test
    void aTryChecksLevelPriceAndTamahagane() {
        Forge f = Forge.DEFAULT;
        Random r = new Random(1);
        assertEquals(Forge.Result.LEVEL_TOO_LOW, f.upgrade(5, 19, 100, null, false, null, r).result);
        assertEquals(Forge.Result.CANNOT_PAY, f.upgrade(5, 20, 2, null, false, null, r).result);
        assertEquals(Forge.Result.NEEDS_TAMAHAGANE, f.upgrade(17, 99, 100, null, false, null, r).result);
        assertEquals(Forge.Result.MAXED, f.upgrade(18, 99, 100, null, true, null, r).result);
        Forge.Outcome first = f.upgrade(0, 1, 1, null, false, null, r);
        assertEquals(Forge.Result.UPGRADED, first.result);
        assertEquals(1, first.enhancement);
        assertFalse(f.upgrade(5, 1, 0, null, false, null, r).tried());
    }

    @Test
    void failuresRegressAndTheTalismanHolds() {
        Forge f = Forge.DEFAULT;
        Random r = new Random(3);
        int regressed = 0;
        int held = 0;
        int tries = 20_000;
        for (int i = 0; i < tries; i++) {
            Forge.Outcome o = f.upgrade(10, 50, 100, null, false, SealTier.P50, r);
            if (o.result == Forge.Result.REGRESSED) {
                regressed++;
                assertEquals(9, o.enhancement);
            }
            if (o.sealSaved) {
                held++;
                assertEquals(10, o.enhancement);
            }
        }
        // +11: 3.87% chance; a failure regresses with 3.87%, half of them held by the talisman
        double expected = (1 - 0.0387) * 0.0387 * tries;
        assertEquals(expected / 2, regressed, expected * 0.15);
        assertEquals(expected / 2, held, expected * 0.15);
    }

    @Test
    void dropsComeInTheVictimsTier() {
        assertSame(StoneTier.COMMON, Drops.tier(20, false));
        assertSame(StoneTier.UNCOMMON, Drops.tier(40, false));
        assertSame(StoneTier.RARE, Drops.tier(80, false));
        assertSame(StoneTier.EPIC, Drops.tier(150, false));
        assertSame(StoneTier.LEGENDARY, Drops.tier(300, false));
        assertSame(StoneTier.MYTHIC, Drops.tier(301, false));
        assertSame(StoneTier.MYTHIC, Drops.tier(20, true), "bosses");
        assertSame(SealTier.P25, Drops.seal(StoneTier.UNCOMMON));
        assertSame(SealTier.P100, Drops.seal(StoneTier.MYTHIC));
        Drops always = new Drops(1, 1, 1, 1);
        Drops.Roll zombie = always.roll(20, false, new Random());
        assertSame(StoneTier.COMMON, zombie.stone);
        assertSame(SealTier.P25, zombie.seal);
        assertFalse(zombie.tamahagane, "no tamahagane below legendary");
        assertTrue(always.roll(200, false, new Random()).tamahagane);
        assertFalse(new Drops(0, 0, 0, 0).roll(1000, true, new Random()).any());
    }

    @Test
    void keysMatchTheFishingItems() {
        assertSame(StoneTier.EPIC, StoneTier.parse("epica"));
        assertSame(StoneTier.LEGENDARY, StoneTier.parse("Lendária"));
        assertSame(SealTier.P75, SealTier.parse("75"));
        assertSame(SealTier.P100, SealTier.parse("100%"));
    }
}
