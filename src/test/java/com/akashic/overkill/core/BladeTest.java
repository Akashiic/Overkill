package com.akashic.overkill.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BladeTest {

    private final Blade b = new Blade("katana/aco", "Katana", 6, 100, "drive_vertical", 0x3333FF);

    @Test
    void attackGrowsWithLevelStarAndFortification() {
        BladeStats s = new BladeStats();
        assertEquals(6, b.attackDamage(s, Leveling.DEFAULT), 1e-9, "level 1, no star, +0");
        s.xp = Leveling.DEFAULT.totalXpFor(21);
        // SlashBlade's refine curve with level - 1 = 20: (1 - 1/(1 + 0.05*20)) * 6 = 3
        assertEquals(9, b.attackDamage(s, Leveling.DEFAULT), 1e-9);
        s.star = Star.OURO;
        s.enhancement = 10;
        assertEquals(9 * 1.25 * 1.4, b.attackDamage(s, Leveling.DEFAULT), 1e-9);
        s.kills = 1000;
        // the fiercer edge doubles f: (1 - 1/(1 + 0.1*20)) * 6 = 4
        assertEquals(10 * 1.25 * 1.4, b.attackDamage(s, Leveling.DEFAULT), 1e-9);
    }

    @Test
    void durabilityGrowsWithFortificationAndBreaking() {
        BladeStats s = new BladeStats();
        assertEquals(100, b.durability(s));
        s.enhancement = Forge.ASCENSION;
        assertEquals(218, b.durability(s));
        s.enhancement = 0;
        assertFalse(s.wear(b, 99));
        assertTrue(s.wear(b, 5), "this broke it");
        assertEquals(100, s.wear, "never past the durability");
        assertTrue(s.broken(b));
        assertEquals(1.5, b.attackDamage(s, Leveling.DEFAULT), 1e-9, "a quarter of the base");
        assertFalse(s.wear(b, 1), "already broken");
        assertEquals(10, s.repairCost());
    }

    @Test
    void badBladesAreRefused() {
        assertEquals(Blade.NO_ART, new Blade("k", "x", 1, 1, null, 0).slashArt);
        assertThrows(IllegalArgumentException.class, () -> new Blade("Katana", "x", 1, 1, "a", 0));
        assertThrows(IllegalArgumentException.class, () -> new Blade("k", "x", 0, 1, "a", 0));
    }
}
