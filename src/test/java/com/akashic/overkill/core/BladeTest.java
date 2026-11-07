package com.akashic.overkill.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BladeTest {

    @Test
    void bladesGrowWithRefineAndKillsAndBreak() {
        Blade b = new Blade("katana/aco", "Katana", 6, 100, "drive_vertical", 0x3333FF, 50, 0.1);
        BladeStats s = new BladeStats();
        assertEquals(6, b.attackDamage(s), 1e-9);
        s.refine = 80;
        s.kills = 5000;
        assertEquals(6 + 5 + 5, b.attackDamage(s), 1e-9, "both capped");
        s.wear(b, 1000);
        assertEquals(100, s.wear);
        assertTrue(s.broken(b));
        assertEquals(1.5, b.attackDamage(s), 1e-9);
        s.proudSoul = 5;
        assertFalse(s.spend(6));
        assertTrue(s.spend(5));
        assertEquals(0, s.proudSoul);
        assertEquals(Blade.NO_ART, new Blade("k", "x", 1, 1, null, 0, 0, 0).slashArt);
        assertThrows(IllegalArgumentException.class, () -> new Blade("Katana", "x", 1, 1, "a", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Blade("k", "x", 0, 1, "a", 0, 0, 0));
    }
}
