package com.akashic.overkill.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class ComboMachineTest {

    private static final Stance SPRINT = new Stance(true, false, true);
    private static final Stance SNEAK = new Stance(true, true, false);
    private static final Stance AIR_SNEAK = new Stance(false, true, false);

    @Test
    void theGroundChainNeedsTheWindow() {
        ComboMachine m = new ComboMachine(Arsenal.KATANA);
        assertEquals("golpe_1", m.attack(100, Stance.GROUND).id);
        Move g1 = Arsenal.KATANA.move("golpe_1");
        // wind-up and hits: spam is ignored
        assertNull(m.attack(101, Stance.GROUND));
        assertNull(m.attack(100 + g1.cancelFrom - 1, Stance.GROUND));
        assertFalse(m.hitting(101));
        assertTrue(m.hitting(102));
        assertTrue(m.hitting(103));
        assertFalse(m.hitting(104));
        assertEquals("golpe_2", m.attack(100 + g1.cancelFrom, Stance.GROUND).id);
        assertEquals(2, m.chain());
        long t = 100 + g1.cancelFrom;
        Move g2 = Arsenal.KATANA.move("golpe_2");
        assertEquals("golpe_3", m.attack(t + g2.cancelTo, Stance.GROUND).id, "last tick of the window");
        t += g2.cancelTo;
        Move g3 = Arsenal.KATANA.move("golpe_3");
        assertEquals("golpe_4", m.attack(t + g3.cancelFrom, Stance.GROUND).id);
        t += g3.cancelFrom;
        // the finisher chains into nothing: an attack during it starts nothing...
        Move g4 = Arsenal.KATANA.move("golpe_4");
        assertNull(m.attack(t + g4.cancelFrom, Stance.GROUND));
        // ...and once it is over, a new combo starts
        assertEquals("golpe_1", m.attack(t + g4.total(), Stance.GROUND).id);
        assertEquals(1, m.chain());
    }

    @Test
    void missingTheWindowStartsOver() {
        ComboMachine m = new ComboMachine(Arsenal.KATANA);
        m.attack(0, Stance.GROUND);
        Move g1 = Arsenal.KATANA.move("golpe_1");
        assertNull(m.current(g1.total()));
        assertEquals("golpe_1", m.attack(g1.total() + 3, Stance.GROUND).id);
    }

    @Test
    void stanceChoosesTheEntryAndFollowUps() {
        assertEquals("estocada", new ComboMachine(Arsenal.KATANA).attack(0, SPRINT).id);
        assertEquals("aereo_1", new ComboMachine(Arsenal.KATANA).attack(0, Stance.AIR).id);
        assertEquals("queda", new ComboMachine(Arsenal.KATANA).attack(0, AIR_SNEAK).id);
        ComboMachine m = new ComboMachine(Arsenal.KATANA);
        assertEquals("lancamento", m.attack(0, SNEAK).id);
        Move launcher = Arsenal.KATANA.move("lancamento");
        // jumped after the launcher: the air chain; stayed on the ground: the ground chain
        assertEquals("aereo_1", m.attack(launcher.cancelFrom, Stance.AIR).id);
        ComboMachine g = new ComboMachine(Arsenal.KATANA);
        g.attack(0, SNEAK);
        assertEquals("golpe_2", g.attack(launcher.cancelFrom, Stance.GROUND).id);
    }

    @Test
    void attackingWhileDrawingIsAQuickDraw() {
        ComboMachine m = new ComboMachine(Arsenal.KATANA);
        m.draw(50);
        assertEquals("iaido", m.attack(55, Stance.GROUND).id);
        ComboMachine late = new ComboMachine(Arsenal.KATANA);
        late.draw(50);
        assertEquals("golpe_1", late.attack(50 + Arsenal.KATANA.drawTicks, Stance.GROUND).id);
        ComboMachine reset = new ComboMachine(Arsenal.KATANA);
        reset.draw(0);
        reset.reset();
        assertEquals("golpe_1", reset.attack(1, Stance.GROUND).id);
    }

    @Test
    void theClientPredictsThePlainChain() {
        Combo k = Arsenal.KATANA;
        assertEquals("golpe_1", k.predicted(null).id);
        assertEquals("golpe_2", k.predicted(k.move("golpe_1")).id);
        assertEquals("golpe_2", k.predicted(k.move("lancamento")).id, "the air follow-up is not plain on the ground");
        assertEquals("aereo_2", k.predicted(k.move("aereo_1")).id);
        assertEquals("golpe_2", k.predicted(k.move("iaido")).id);
        assertNull(k.predicted(k.move("golpe_4")));
        assertNull(k.predicted(k.move("queda")));
    }

    @Test
    void combosAreValidated() {
        Move a = Move.builder("a")
            .next("b")
            .build();
        assertThrows(
            IllegalArgumentException.class,
            () -> new Combo("x", Collections.singletonList(a), Collections.singletonList("a"), null, 10));
        assertThrows(
            IllegalArgumentException.class,
            () -> new Combo(
                "x",
                Collections.singletonList(
                    Move.builder("parada")
                        .build()),
                Collections.<String>emptyList(),
                null,
                10));
        Move b = Move.builder("b")
            .build();
        assertThrows(
            IllegalArgumentException.class,
            () -> new Combo("x", Arrays.asList(b, b), Collections.singletonList("b"), null, 10));
        assertThrows(IllegalArgumentException.class, () -> Move.builder("Golpe"));
        assertThrows(
            IllegalArgumentException.class,
            () -> Move.builder("a")
                .frames(2, 2, 2)
                .cancel(1, 9)
                .build());
    }

    @Test
    void everyArsenalNameIsAPackState() {
        for (Combo c : Arsenal.COMBOS.values()) {
            for (Move m : c.moves()
                .values()) {
                assertTrue(Move.ID.matcher(m.id)
                    .matches(), m.id);
                assertTrue(m.id.length() <= 32);
            }
        }
        for (SlashArt a : Arsenal.SLASH_ARTS.values()) {
            assertFalse(Arsenal.KATANA.moves()
                .containsKey(a.id), "an art is a state of its own");
        }
        for (Blade b : Arsenal.DEFAULT_BLADES) {
            assertTrue(Arsenal.COMBOS.containsKey(b.combo), b.id);
            assertTrue(Arsenal.SLASH_ARTS.containsKey(b.slashArt), b.id);
        }
    }
}
