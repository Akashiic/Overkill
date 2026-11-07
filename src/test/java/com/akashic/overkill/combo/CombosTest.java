package com.akashic.overkill.combo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The combo graph against SlashBlade: Resharped's numbers (docs/fidelidade/estudo/01-combo.md, section 3). */
class CombosTest {

    static final Combos G = Combos.base();

    /** Records what the engine asks of the world. */
    static final class Fake implements Actor {

        int held;
        boolean ground = true;
        boolean powered;
        int use;
        double vy;
        boolean found;
        final List<String> log = new ArrayList<>();

        @Override
        public int inputs() {
            return held | (ground ? Input.ON_GROUND : Input.ON_AIR);
        }

        @Override
        public boolean onGround() {
            return ground;
        }

        @Override
        public boolean inWater() {
            return false;
        }

        @Override
        public boolean isPowered() {
            return powered;
        }

        @Override
        public int useTicks() {
            return use;
        }

        @Override
        public float random() {
            return 0.5f;
        }

        @Override
        public void slash(float roll, Offset offset, boolean mute, boolean critical, float ratio, Knockback kb) {
            log.add("slash " + Math.round(roll) + " " + ratio + (critical ? " crit" : "") + (kb != Knockback.CANCEL ? " " + kb : ""));
        }

        @Override
        public boolean areaAttack(Knockback kb, float ratio, boolean forceHit, boolean resetHit, boolean mute, double reach) {
            log.add("area " + kb + " " + ratio);
            return found;
        }

        @Override
        public void sheathSound() {
            log.add("sheath");
        }

        @Override
        public void sound(Sound sound, float volume, float pitch) {
            log.add("sound " + sound);
        }

        @Override
        public double velocityY() {
            return vy;
        }

        @Override
        public void setVelocityY(double v) {
            vy = v;
            log.add("vy " + v);
        }

        @Override
        public void setVelocity(double x, double y, double z) {
            vy = y;
            log.add("vel " + x + " " + y + " " + z);
        }

        @Override
        public void addForward(double speed) {
            log.add("forward " + speed);
        }

        @Override
        public void stopHorizontal() {}

        @Override
        public void fallDecrease() {}

        @Override
        public void fallResist() {}

        @Override
        public void setFallDistance(float distance) {}

        @Override
        public void spin(float degrees) {}

        @Override
        public void art(Art art, float... args) {
            log.add("art " + art);
        }

        List<String> slashes() {
            List<String> out = new ArrayList<>();
            for (String s : log) {
                if (s.startsWith("slash")) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    @Test
    void durationsExpiriesAndLocksMatchSlashBlade() {
        // id, timeoutMs, expires (first elapsed tick out), lock ticks (-1: no lock window)
        Object[][] table = { { "combo_a1", 300, 7, 3 }, { "combo_a1_end", 366, 8, -1 }, { "combo_a1_end2", 666, 14, -1 },
            { "combo_a2", 500, 11, 3 }, { "combo_c", 1966, 40, 10 }, { "combo_c_end", 966, 20, -1 },
            { "combo_a3", 600, 13, 6 }, { "combo_a3_end2", 1700, 35, -1 }, { "combo_a4", 2533, 51, 14 },
            { "combo_a4_ex", 1300, 27, 14 }, { "combo_a5ex", 3766, 76, 22 }, { "combo_b1", 666, 14, 8 },
            { "combo_b1_end", 766, 16, -1 }, { "combo_b2", 333, 7, 4 }, { "combo_b7", 1800, 37, 22 },
            { "aerial_rave_a1", 733, 15, 3 }, { "aerial_rave_a3", 933, 19, 6 }, { "aerial_rave_b3", 1233, 25, 6 },
            { "upperslash", 1966, 40, 7 }, { "upperslash_jump", 433, 9, 4 }, { "aerial_cleave", 400, 9, -1 },
            { "aerial_cleave_loop", 1166, 24, -1 }, { "aerial_cleave_landing", 1433, 29, 4 },
            { "rapid_slash", 633, 13, -1 }, { "rapid_slash_quick", 33, 1, -1 }, { "rising_star", 1233, 25, 12 },
            { "judgement_cut", 766, 16, -1 }, { "judgement_cut_slash", 416, 9, -1 },
            { "judgement_cut_slash_air", 333, 7, -1 }, { "judgement_cut_slash_just", 166, 4, -1 },
            { "judgement_cut_slash_just2", 222, 5, -1 }, { "void_slash", 2566, 52, -1 },
            { "sakura_end_left", 238, 5, -1 }, { "sakura_end_right", 424, 9, -1 },
            { "sakura_end_left_air", 291, 6, -1 }, { "circle_slash", 600, 13, -1 }, { "drive_vertical", 1966, 40, 10 },
            { "standby", 1033, 21, -1 } };
        for (Object[] row : table) {
            ComboState s = G.get((String) row[0]);
            assertEquals(row[0], s.id, "exists");
            assertEquals((int) row[1], s.timeoutMs(), row[0] + " ms");
            assertEquals((int) row[2], s.expiresAt(), row[0] + " expires");
            int lock = (int) row[3];
            if (lock >= 0) {
                ComboMachine m = new ComboMachine(G, null);
                Fake f = new Fake();
                m.enter(s.id, 0, f, ComboMachine.Cause.RESET);
                ComboState.Ctx early = ctx(m, f, lock - 1);
                ComboState.Ctx open = ctx(m, f, lock);
                assertTrue(s.next.locked(early), row[0] + " locked at " + (lock - 1));
                assertEquals(s.id, s.next.apply(early), row[0] + " ignores clicks before " + lock);
                assertTrue(!s.next.locked(open), row[0] + " open at " + lock);
            }
        }
    }

    private static ComboState.Ctx ctx(ComboMachine m, Actor a, long elapsed) {
        return new ComboState.Ctx(m, a, m.state(), elapsed, elapsed, 0, a.inputs() | Input.R_CLICK);
    }

    @Test
    void everyTargetExists() {
        for (ComboState s : G.all()) {
            assertTrue(G.has(s.nextOfTimeout), s.id + " -> " + s.nextOfTimeout);
        }
        for (Combos.RootCommand r : rootRows()) {
            assertTrue(G.has(r.state), r.state);
        }
        assertEquals(java.util.Arrays.asList("combo_a1", "combo_a1_end", "combo_a1_end2"), ids(G.chain("combo_a1", 16)));
        assertEquals(java.util.Arrays.asList("combo_b2", "combo_b_end", "combo_b_end2", "combo_b_end3"), ids(G.chain("combo_b2", 16)));
    }

    private static List<Combos.RootCommand> rootRows() {
        List<Combos.RootCommand> rows = new ArrayList<>();
        for (String s : new String[] { "rapid_slash", "upperslash", "combo_a1", "aerial_cleave", "aerial_rave_a1" }) {
            rows.add(new Combos.RootCommand(0, s));
        }
        return rows;
    }

    private static List<String> ids(List<ComboState> states) {
        List<String> out = new ArrayList<>();
        for (ComboState s : states) {
            out.add(s.id);
        }
        return out;
    }

    @Test
    void rootCommandsPickTheStrongestMatch() {
        int g = Input.ON_GROUND;
        assertEquals("combo_a1", G.rootCommand(g | Input.R_CLICK));
        assertEquals("combo_a1", G.rootCommand(g | Input.L_CLICK));
        assertEquals("rapid_slash", G.rootCommand(g | Input.R_CLICK | Input.SNEAK | Input.FORWARD));
        assertEquals("upperslash", G.rootCommand(g | Input.R_CLICK | Input.SNEAK | Input.BACK));
        assertEquals("rapid_slash", G.rootCommand(g | Input.R_CLICK | Input.SNEAK | Input.BACK | Input.FORWARD), "70 beats 90");
        assertEquals("aerial_rave_a1", G.rootCommand(Input.ON_AIR | Input.R_CLICK));
        assertEquals("aerial_cleave", G.rootCommand(Input.ON_AIR | Input.R_CLICK | Input.SNEAK | Input.BACK));
        assertEquals(Combos.NONE, G.rootCommand(g), "a ground row needs a click");
    }

    @Test
    void groundComboFlowsWithLockWindowsAndNoBuffer() {
        ComboMachine m = new ComboMachine(G, null);
        Fake f = new Fake();
        long now = 100;
        assertEquals("combo_a1", m.click(now, f, f.inputs() | Input.R_CLICK));
        assertEquals(java.util.Arrays.asList("slash -10 0.44"), f.slashes(), "A1 slashes on the click");
        assertNull(m.click(now + 2, f, f.inputs() | Input.R_CLICK), "inside the lock window: dropped");
        assertEquals("combo_a2", m.click(now + 3, f, f.inputs() | Input.R_CLICK));
        assertEquals("combo_a3", m.click(now + 6, f, f.inputs() | Input.R_CLICK));
        // A3's timeline: keys 2 and 6, counted from its first processed tick
        f.log.clear();
        for (long t = now + 7; t <= now + 14; t++) {
            m.tick(t, f);
            if (t == now + 9) {
                assertEquals(1, f.slashes().size(), "key 2 = the third tick");
            }
        }
        assertEquals(java.util.Arrays.asList("slash -61 0.44", "slash 138 0.44"), f.slashes());
        // the rapid slash root command (70) overrides the continuation (100)
        f.held = Input.SNEAK | Input.FORWARD;
        assertEquals("rapid_slash", m.click(now + 15, f, f.inputs() | Input.R_CLICK));
    }

    @Test
    void timeoutsAdvanceOnePerTickAndPoweredBranches() {
        ComboMachine m = new ComboMachine(G, null);
        Fake f = new Fake();
        m.click(0, f, f.inputs() | Input.R_CLICK);
        for (long t = 1; t < 7; t++) {
            m.tick(t, f);
            assertEquals("combo_a1", m.state().id, "tick " + t);
        }
        m.tick(7, f);
        assertEquals("combo_a1_end", m.state().id, "expires at elapsed 7");
        assertEquals("combo_a2", m.click(8, f, f.inputs() | Input.R_CLICK), "A1_end still continues");
        f.powered = true;
        m.click(11, f, f.inputs() | Input.R_CLICK);
        assertEquals("combo_a3", m.state().id);
        assertEquals("combo_a4_ex", m.click(17, f, f.inputs() | Input.R_CLICK), "Strength or Hunger: A4ex");
    }

    @Test
    void aerialComboLandsIntoItsEnd() {
        ComboMachine m = new ComboMachine(G, null);
        Fake f = new Fake();
        f.ground = false;
        assertEquals("aerial_rave_a1", m.click(0, f, f.inputs() | Input.R_CLICK));
        f.ground = true;
        m.land(1, f);
        assertEquals("aerial_rave_a1_end", m.state().id);
    }

    @Test
    void chargeJustAndQuickChargeStartSlashArts() {
        Fake f = new Fake();
        ComboMachine m = new ComboMachine(G, null);
        assertEquals("judgement_cut_slash_just", m.release(0, f, 10, SlashArt.JUDGEMENT_CUT, 0), "9..11 ticks: just");
        m = new ComboMachine(G, null);
        assertEquals("judgement_cut", m.release(0, f, 15, SlashArt.JUDGEMENT_CUT, 0), "longer: success");
        m = new ComboMachine(G, null);
        assertNull(m.release(0, f, 5, SlashArt.JUDGEMENT_CUT, 0), "short: fail");
        // inside an attack's lock window nothing happens
        m.click(10, f, f.inputs() | Input.R_CLICK);
        assertNull(m.release(11, f, 15, SlashArt.JUDGEMENT_CUT, 0));
        // quick charge: released within 3 ticks of a sheathing state with a short hold
        m.enter("combo_a3_end3", 100, f, ComboMachine.Cause.RESET);
        assertEquals("judgement_cut_slash_just", m.release(102, f, 5, SlashArt.JUDGEMENT_CUT, 0));
        // in the air the plain success is the air slash
        f.ground = false;
        m = new ComboMachine(G, null);
        assertEquals("judgement_cut_slash_air", m.release(0, f, 15, SlashArt.JUDGEMENT_CUT, 0));
    }

    @Test
    void aerialCleaveDivesAndLands() {
        ComboMachine m = new ComboMachine(G, null);
        Fake f = new Fake();
        f.ground = false;
        f.held = Input.SNEAK | Input.BACK;
        assertEquals("aerial_cleave", m.click(0, f, f.inputs() | Input.R_CLICK));
        m.tick(1, f);
        assertEquals("aerial_cleave_loop", m.state().id, "about one tick, then the loop");
        m.tick(2, f);
        m.tick(3, f);
        assertTrue(f.vy < -5, "falls 3 blocks/tick faster each tick");
        f.ground = true;
        m.tick(4, f);
        assertEquals("aerial_cleave_landing", m.state().id);
        assertTrue(f.log.contains("slash 55 0.44 crit METEOR") && f.log.contains("slash 60 0.44 METEOR"));
    }

    @Test
    void rapidSlashDashesAndRisesIntoRisingStar() {
        ComboMachine m = new ComboMachine(G, null);
        Fake f = new Fake();
        f.held = Input.SNEAK | Input.FORWARD;
        assertEquals("rapid_slash", m.click(0, f, f.inputs() | Input.R_CLICK));
        for (long t = 1; t <= 3; t++) {
            m.tick(t, f);
        }
        assertEquals(3, f.log.stream()
            .filter(s -> s.startsWith("forward"))
            .count(), "dash on elapsed 1..3");
        f.use = 2;
        f.held |= Input.R_DOWN;
        f.found = true;
        m.hold(4, f);
        assertEquals("rising_star", m.state().id);
        assertTrue(f.log.contains("vel 0.0 0.6 0.0"));
    }
}
