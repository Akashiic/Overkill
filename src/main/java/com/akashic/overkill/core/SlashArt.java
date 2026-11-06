package com.akashic.overkill.core;

import java.util.regex.Pattern;

/**
 * A blade's big move, released after holding the sword raised for {@link #chargeTicks}. Its id is also the animation
 * state of the held model. Immutable.
 */
public final class SlashArt {

    public static final Pattern ID = Move.ID;

    /** What the art does in the world. */
    public enum Kind {
        /** Cuts space itself at a distance: a burst of slashes where the player looks (or on the locked target). */
        DIMENSION,
        /** A crescent wave that flies straight ahead and cuts everything on its way. */
        WAVE,
        /** One spinning cut all around the player. */
        CIRCLE
    }

    public final String id;
    public final String name;
    public final Kind kind;
    public final int chargeTicks;
    public final int cost;
    /** Times the blade's attack damage, per hit. */
    public final double damage;
    /** How far it reaches (DIMENSION: distance of the point; WAVE: flight; CIRCLE: radius), blocks. */
    public final double range;
    /** Radius of the cut around its point (DIMENSION) or the wave's path (WAVE), blocks. */
    public final double radius;
    /** Hits per target (DIMENSION hits several times). */
    public final int hits;
    public final int style;

    public SlashArt(String id, String name, Kind kind, int chargeTicks, int cost, double damage, double range,
        double radius, int hits, int style) {
        if (id == null || !ID.matcher(id)
            .matches()) {
            throw new IllegalArgumentException("slash art id must match " + ID + ": " + id);
        }
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.chargeTicks = Math.max(1, chargeTicks);
        this.cost = Math.max(0, cost);
        this.damage = damage;
        this.range = range;
        this.radius = radius;
        this.hits = Math.max(1, hits);
        this.style = style;
    }
}
