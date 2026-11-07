package com.akashic.overkill.core;

/**
 * How a katana levels up, 1 to {@link #MAX_LEVEL}, by killing with it. From level {@code L} to {@code L+1} takes
 * {@code round(base + factor * L^exponent)} XP (the AkashicFishing rods' curve: 20 + 1.37 L^1.5, 54,732 XP to 99).
 * A kill gives half the victim's max health (a zombie 10), times {@code 1 + 0.1 * rank level} (the concentration rank,
 * as SlashBlade's proud soul gain), between 1 and {@link #MAX_KILL_XP}. About 4,500 zombies to 99. Immutable.
 */
public final class Leveling {

    public static final int MAX_LEVEL = 99;
    public static final int MAX_KILL_XP = 400;
    /** SlashBlade's {@code FIERCEREDGE}: from this many kills the level adds attack twice as fast. */
    public static final int FIERCER_EDGE_KILLS = 1000;

    public static final Leveling DEFAULT = new Leveling(20, 1.37, 1.5);

    private final double base;
    private final double factor;
    private final double exponent;

    public Leveling(double base, double factor, double exponent) {
        if (!(base >= 1) || !(factor >= 0) || !(exponent >= 0) || !Double.isFinite(base + factor + exponent)) {
            throw new IllegalArgumentException("leveling must have base >= 1, factor and exponent >= 0");
        }
        this.base = base;
        this.factor = factor;
        this.exponent = exponent;
    }

    /** XP from {@code level} to the next; 0 at the top. */
    public long xpToNext(int level) {
        if (level >= MAX_LEVEL) {
            return 0;
        }
        return Math.round(base + factor * Math.pow(Math.max(1, level), exponent));
    }

    /** Total XP to reach {@code level} from 1. */
    public long totalXpFor(int level) {
        long total = 0;
        for (int l = 1; l < Math.min(level, MAX_LEVEL + 1); l++) {
            total += xpToNext(l);
        }
        return total;
    }

    public int levelOf(long xp) {
        int level = 1;
        long left = xp;
        while (level < MAX_LEVEL && left >= xpToNext(level)) {
            left -= xpToNext(level);
            level++;
        }
        return level;
    }

    /** XP gathered inside the current level. */
    public long xpIntoLevel(long xp) {
        return xp - totalXpFor(levelOf(xp));
    }

    /** XP a kill gives: half the victim's max health, more at a higher concentration rank. */
    public static long xpForKill(double victimMaxHealth, float rankLevel) {
        double xp = victimMaxHealth / 2 * (1 + 0.1 * Math.max(0, rankLevel));
        return Math.max(1, Math.min(MAX_KILL_XP, Math.round(xp)));
    }

    /** SlashBlade's proud soul for a kill: {@code min(100, floor(droppedXp * (1 + 0.1 * rank level)))}. */
    public static int proudSoulForKill(int droppedXp, float rankLevel) {
        return (int) Math.min(100, Math.floor(Math.max(0, droppedXp) * (1 + 0.1 * Math.max(0, rankLevel))));
    }

    /**
     * The attack the level adds, SlashBlade's refine formula with the level in place of the refines:
     * {@code (1 - 1 / (1 + f * (level - 1))) * base}, f = 0.05 (0.1 from 1,000 kills): diminishing, never past +100%.
     */
    public static double amplifier(int level, int kills, double baseDamage) {
        double f = kills >= FIERCER_EDGE_KILLS ? 0.1 : 0.05;
        return (1 - 1 / (1 + f * Math.max(0, level - 1))) * baseDamage;
    }
}
