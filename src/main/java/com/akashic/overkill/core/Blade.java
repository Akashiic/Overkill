package com.akashic.overkill.core;

import java.util.regex.Pattern;

/**
 * A kind of blade, as configured: what it hits for, how long it lasts, its slash art and the colour of its effects. The instance in a
 * player's hand carries its own {@link BladeStats}. Immutable.
 */
public final class Blade {

    /** Ids are the item model's path in the pack too ({@code overkill:<id>}). */
    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}(/[a-z0-9_]{1,32})?");

    public final String id;
    public final String name;
    public final double baseDamage;
    public final int durability;
    /** The slash art's id ({@code judgement_cut} ...) or {@link #NO_ART}. */
    public final String slashArt;
    /** The effects' colour (slash arcs, drives, the lock-on), RGB. */
    public final int color;
    /** Refines that still add damage, and how much each adds. */
    public final int refineCap;
    public final double refineStep;

    public static final String NO_ART = "nenhuma";

    public Blade(String id, String name, double baseDamage, int durability, String slashArt, int color,
        int refineCap, double refineStep) {
        if (id == null || !ID.matcher(id)
            .matches()) {
            throw new IllegalArgumentException("blade id must match " + ID + ": " + id);
        }
        if (!(baseDamage > 0) || durability < 1 || refineCap < 0 || refineStep < 0) {
            throw new IllegalArgumentException(id + ": damage > 0, durability >= 1, refine >= 0");
        }
        this.id = id;
        this.name = name;
        this.baseDamage = baseDamage;
        this.durability = durability;
        this.slashArt = slashArt == null ? NO_ART : slashArt;
        this.color = color & 0xFFFFFF;
        this.refineCap = refineCap;
        this.refineStep = refineStep;
    }

    /**
     * The attack damage of this blade with these stats: base, plus each refine up to the cap, plus up to 5 for kills
     * (one per 200); a broken blade hits for a quarter of its base.
     */
    public double attackDamage(BladeStats s) {
        if (s.broken(this)) {
            return baseDamage * 0.25;
        }
        return baseDamage + Math.min(s.refine, refineCap) * refineStep + Math.min(s.kills, 1000) / 200.0;
    }
}
