package com.akashic.overkill.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoreMathTest {

    @Test
    void minecraftAngles() {
        double[] south = Geometry.look(0, 0);
        assertEquals(1, south[2], 1e-9);
        double[] west = Geometry.look(90, 0);
        assertEquals(-1, west[0], 1e-9);
        double[] down = Geometry.look(0, 90);
        assertEquals(-1, down[1], 1e-9);
        assertEquals(0, Geometry.yawTo(0, 0, 0, 5), 1e-9);
        assertEquals(90, Geometry.yawTo(0, 0, -5, 0), 1e-9);
        assertEquals(-90, Geometry.yawTo(0, 0, 5, 0), 1e-9);
        assertEquals(45, Geometry.pitchTo(0, 1, 0, 0, 0, 1), 1e-9);
        assertEquals(-170, Geometry.wrap(190), 1e-9);
    }

    @Test
    void swingsHitInFrontWithinReachAndArc() {
        // a zombie-sized box 2 blocks south of eyes looking south
        assertTrue(Geometry.swingHits(0, 1.6, 0, 0, 3.5, 110, 0, 1, 2, 0.3, 0.95));
        // out of reach
        assertFalse(Geometry.swingHits(0, 1.6, 0, 0, 3.5, 110, 0, 1, 5, 0.3, 0.95));
        // behind
        assertFalse(Geometry.swingHits(0, 1.6, 0, 0, 3.5, 110, 0, 1, -2, 0.3, 0.95));
        // behind, but a 360 swing
        assertTrue(Geometry.swingHits(0, 1.6, 0, 0, 3.5, 360, 0, 1, -2, 0.3, 0.95));
        // at the edge of the arc: 60 degrees off a 110 swing is out, unless the target is wide
        double x = -2 * Math.sin(Math.toRadians(60));
        double z = 2 * Math.cos(Math.toRadians(60));
        assertFalse(Geometry.swingHits(0, 1.6, 0, 0, 3.5, 110, -x, 1, z, 0.1, 0.95));
        assertTrue(Geometry.swingHits(0, 1.6, 0, 0, 3.5, 110, -x, 1, z, 0.6, 0.95));
        // a target the attacker stands in
        assertTrue(Geometry.swingHits(0, 1.6, 0, 180, 3.5, 30, 0.1, 1, 0.1, 0.5, 1));
    }

    @Test
    void picking() {
        double[] dir = Geometry.look(0, 0);
        double[] r = Geometry.alongRay(0, 0, 0, dir, 1, 0, 10);
        assertEquals(10, r[0], 1e-9);
        assertEquals(1, r[1], 1e-9);
        assertEquals(90, Geometry.angleTo(dir, 0, 0, 0, 5, 0, 0), 1e-9);
    }

    @Test
    void styleRankRisesWithVarietyAndFalls() {
        StyleRank r = new StyleRank();
        assertEquals(-1, r.rank());
        assertEquals("", r.name());
        assertEquals(40, r.hit("golpe_1", 40, 0), 1e-9);
        assertEquals(20, r.hit("golpe_1", 40, 1), 1e-9, "the same move again: halved");
        assertEquals(10, r.hit("golpe_1", 40, 2), 1e-9);
        assertEquals(40, r.hit("golpe_2", 40, 3), 1e-9, "another move: full");
        assertEquals("C", r.name());
        for (int i = 0; i < 100; i++) {
            r.hit("m" + (i % 7), 60, 4 + i);
        }
        assertEquals("SSS", r.name());
        assertEquals(StyleRank.MAX, r.points(), 1e-9);
        assertEquals(1, r.progress(), 1e-6);
        // no drain during the grace period, then a drain
        r.tick(103 + StyleRank.GRACE);
        assertEquals(StyleRank.MAX, r.points(), 1e-9);
        r.tick(104 + StyleRank.GRACE);
        assertTrue(r.points() < StyleRank.MAX);
        r.hurt(5);
        assertTrue(r.points() < StyleRank.MAX - 150);
        r.reset();
        assertEquals(0, r.points(), 0);
        r.bonus(150, 0);
        assertEquals("C", r.name());
        assertEquals(0.333F, r.progress(), 1e-2);
    }

    @Test
    void chargeNeedsTheFullHold() {
        ChargeMeter c = new ChargeMeter();
        assertFalse(c.release(0, 10), "never held");
        c.hold(100);
        c.hold(101);
        assertEquals(0.5F, c.progress(105, 10), 1e-6);
        assertFalse(c.release(105, 10));
        assertFalse(c.holding());
        c.hold(200);
        assertTrue(c.release(210, 10));
        c.hold(300);
        c.cancel();
        assertFalse(c.release(400, 10));
    }

    @Test
    void bladesGrowWithRefineAndKillsAndBreak() {
        Blade b = new Blade("katana/basica", "Katana", 6, 100, "katana", "onda", 50, 0.1);
        BladeStats s = new BladeStats();
        assertEquals(6, b.attackDamage(s), 1e-9);
        s.refine = 80;
        s.kills = 5000;
        assertEquals(6 + 5 + 5, b.attackDamage(s), 1e-9, "both capped");
        s.wear(b, 1000);
        assertEquals(100, s.wear);
        assertTrue(s.broken(b));
        assertEquals(1.5, b.attackDamage(s), 1e-9);
        assertEquals(10, s.repairCost());
        assertEquals(10 + 80 * 5, s.refineCost());
        s.proudSoul = 5;
        assertFalse(s.spend(6));
        assertTrue(s.spend(5));
        assertEquals(0, s.proudSoul);
        assertEquals(5, BladeStats.proudSoulFor(20, -1));
        assertEquals(10, BladeStats.proudSoulFor(20, 4), "S rank doubles it");
        assertEquals(1, BladeStats.proudSoulFor(0.5, 0));
        assertThrows(IllegalArgumentException.class, () -> new Blade("Katana", "x", 1, 1, "k", "a", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Blade("k", "x", 0, 1, "k", "a", 0, 0));
    }
}
