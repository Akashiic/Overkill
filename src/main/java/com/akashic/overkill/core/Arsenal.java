package com.akashic.overkill.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything Overkill ships: the combos, the slash arts and the default blades. The plugin plays these moves and the
 * pack generator draws them and writes their {@code ao_atacar} from the same objects, so the server's chain windows
 * and the client's predictions cannot drift apart. A move needs an animation, so moves are code; blades (damage,
 * durability, which combo and art) are configuration ({@code blades.yml}).
 */
public final class Arsenal {

    private Arsenal() {}

    /** The katana's combo: a four-hit ground chain, a lunge, a launcher, an air chain, a plunge and a quick-draw. */
    public static final Combo KATANA = new Combo(
        "katana",
        Arrays.asList(
            Move.builder("golpe_1")
                .when(Condition.GROUND)
                .frames(2, 2, 10)
                .damage(1.0)
                .area(3.5, 110)
                .style(10)
                .next("golpe_2")
                .build(),
            Move.builder("golpe_2")
                .frames(2, 2, 10)
                .damage(1.1)
                .area(3.5, 120)
                .style(10)
                .next("golpe_3")
                .build(),
            Move.builder("golpe_3")
                .frames(3, 2, 11)
                .damage(1.25)
                .area(3.8, 100)
                .style(12)
                .next("golpe_4")
                .build(),
            Move.builder("golpe_4")
                .frames(4, 3, 14)
                .damage(1.8)
                .area(4.0, 160)
                .push(0.9)
                .style(25)
                .build(),
            Move.builder("estocada")
                .when(Condition.SPRINT)
                .frames(3, 4, 10)
                .damage(1.4)
                .area(4.5, 50)
                .lunge(1.1)
                .push(0.6)
                .style(18)
                .next("golpe_3")
                .build(),
            Move.builder("lancamento")
                .when(Condition.SNEAK)
                .frames(3, 2, 12)
                .damage(1.0)
                .area(3.5, 100)
                .push(0.05)
                .launch(0.75)
                .style(20)
                .next("aereo_1", "golpe_2")
                .build(),
            Move.builder("aereo_1")
                .when(Condition.AIR)
                .frames(2, 2, 9)
                .damage(0.9)
                .area(3.5, 120)
                .push(0.05)
                .launch(0.35)
                .lift(0.3)
                .style(12)
                .next("aereo_2")
                .build(),
            Move.builder("aereo_2")
                .frames(2, 2, 9)
                .damage(1.0)
                .area(3.5, 120)
                .push(0.05)
                .launch(0.35)
                .lift(0.3)
                .style(12)
                .next("aereo_3")
                .build(),
            Move.builder("aereo_3")
                .frames(3, 2, 12)
                .damage(1.3)
                .area(3.8, 140)
                .push(0.8)
                .launch(-0.6)
                .lift(0.2)
                .style(20)
                .build(),
            Move.builder("queda")
                .when(Condition.AIR_SNEAK)
                .frames(2, 6, 10)
                .damage(1.6)
                .area(3.0, 360)
                .push(0.3)
                .lift(-1.4)
                .style(22)
                .build(),
            Move.builder("iaido")
                .frames(1, 2, 10)
                .damage(2.0)
                .area(4.0, 140)
                .push(0.5)
                .style(25)
                .next("golpe_2")
                .build()),
        Arrays.asList("queda", "aereo_1", "estocada", "lancamento", "golpe_1"),
        "iaido",
        10);

    /** Slash arts by id. */
    public static final Map<String, SlashArt> SLASH_ARTS;

    static {
        Map<String, SlashArt> arts = new LinkedHashMap<>();
        for (SlashArt a : Arrays.asList(
            new SlashArt("corte_dimensional", "Corte Dimensional", SlashArt.Kind.DIMENSION, 14, 12, 0.8, 14, 3.2, 5, 60),
            new SlashArt("onda", "Onda Cortante", SlashArt.Kind.WAVE, 12, 8, 1.6, 18, 1.6, 1, 45),
            new SlashArt("corte_circular", "Corte Circular", SlashArt.Kind.CIRCLE, 10, 6, 1.4, 5, 5, 2, 40))) {
            arts.put(a.id, a);
        }
        SLASH_ARTS = Collections.unmodifiableMap(arts);
    }

    /** Combos by id. */
    public static final Map<String, Combo> COMBOS = Collections.singletonMap(KATANA.id, KATANA);

    /** The blades a fresh install offers (blades.yml can change them and add others). */
    public static final List<Blade> DEFAULT_BLADES = Collections.unmodifiableList(
        Arrays.asList(
            new Blade("katana/basica", "Katana", 6, 600, "katana", "corte_dimensional", 50, 0.1),
            new Blade("katana/rubra", "Lâmina Rubra", 7, 800, "katana", "onda", 50, 0.1),
            new Blade("katana/tempestade", "Tempestade", 6.5, 700, "katana", "corte_circular", 50, 0.1)));

    /** Summoned swords: a volley that homes in on the target. */
    public static final class Summoned {

        public static final int SWORDS = 3;
        /** Blocks per tick. */
        public static final double SPEED = 1.3;
        /** Times the blade's attack damage, per sword. */
        public static final double DAMAGE = 0.45;
        public static final int COOLDOWN = 14;
        public static final int LIFETIME = 40;
        public static final int COST = 1;
        public static final int STYLE = 8;

        private Summoned() {}
    }

    /** The dodge: a dash with a few invulnerable ticks. */
    public static final class Dodge {

        public static final double SPEED = 1.0;
        public static final int INVULNERABLE = 6;
        public static final int COOLDOWN = 16;
        /** Style for an attack dodged inside the invulnerable ticks. */
        public static final int STYLE = 30;

        private Dodge() {}
    }

    /** The air trick: a blink next to the locked target. */
    public static final class AirTrick {

        public static final int COOLDOWN = 40;
        public static final int COST = 2;
        public static final double RANGE = 20;
        public static final int STYLE = 15;

        private AirTrick() {}
    }

    /** Lock-on. */
    public static final class LockOn {

        public static final double RANGE = 20;
        public static final double CONE = 35;
        public static final float STRENGTH = 0.3F;

        private LockOn() {}
    }
}
