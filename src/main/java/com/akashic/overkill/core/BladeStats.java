package com.akashic.overkill.core;

/**
 * What one blade has lived through, stored in the item (persistent data): kills, proud soul (the blade's currency,
 * earned by killing, spent on slash arts, refining and repair), refines, and wear (durability used). Mutable value;
 * the plugin reads it from the item, changes it and writes it back.
 */
public final class BladeStats {

    public int kills;
    public int proudSoul;
    public int refine;
    public int wear;

    public BladeStats() {}

    public BladeStats(int kills, int proudSoul, int refine, int wear) {
        this.kills = Math.max(0, kills);
        this.proudSoul = Math.max(0, proudSoul);
        this.refine = Math.max(0, refine);
        this.wear = Math.max(0, wear);
    }

    /** A broken blade still cuts, weakly, and cannot use slash arts until repaired. */
    public boolean broken(Blade b) {
        return wear >= b.durability;
    }

    /** Wears the blade by {@code amount} (not past its durability). */
    public void wear(Blade b, int amount) {
        wear = Math.min(b.durability, wear + Math.max(0, amount));
    }

    /** Proud soul for a kill: a quarter of the victim's max health, more at higher style ranks; at least 1. */
    public static int proudSoulFor(double victimMaxHealth, int styleRank) {
        double rankFactor = 1 + 0.25 * Math.max(0, styleRank);
        return (int) Math.max(1, Math.round(victimMaxHealth / 4 * rankFactor));
    }

    /** Proud soul the next refine costs. */
    public int refineCost() {
        return 10 + refine * 5;
    }

    /** Proud soul a full repair costs (one per 10 wear, rounded up). */
    public int repairCost() {
        return (wear + 9) / 10;
    }

    /** Spends {@code amount} proud soul if there is enough. */
    public boolean spend(int amount) {
        if (amount < 0 || proudSoul < amount) {
            return false;
        }
        proudSoul -= amount;
        return true;
    }

    public BladeStats copy() {
        return new BladeStats(kills, proudSoul, refine, wear);
    }
}
