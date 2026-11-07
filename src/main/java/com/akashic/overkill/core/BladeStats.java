package com.akashic.overkill.core;

/**
 * What one katana has lived through, stored in the item: its XP (the level), kills, proud soul (SlashBlade's currency,
 * earned by killing, spent on slash arts and repairs), fortification (+0 .. +17, 18 the Ascension), star (null until
 * rolled, the first time it is held) and wear. Mutable value; the plugin reads it from the item, changes it and writes
 * it back.
 */
public final class BladeStats {

    /** SlashBlade's slash art cost; without it the blade takes one wear instead. */
    public static final int ART_COST = 20;

    public long xp;
    public int kills;
    public int proudSoul;
    public int enhancement;
    public Star star;
    public int wear;
    public String maker;

    public BladeStats() {}

    public BladeStats(long xp, int kills, int proudSoul, int enhancement, Star star, int wear, String maker) {
        this.xp = Math.max(0, xp);
        this.kills = Math.max(0, kills);
        this.proudSoul = Math.max(0, proudSoul);
        this.enhancement = Math.max(0, Math.min(Forge.ASCENSION, enhancement));
        this.star = star;
        this.wear = Math.max(0, wear);
        this.maker = maker;
    }

    public int level(Leveling leveling) {
        return leveling.levelOf(xp);
    }

    /** The star's multiplier, 1 while not rolled. */
    public double starMultiplier() {
        return star == null ? 1 : star.multiplier();
    }

    /** A broken blade still cuts, weakly, and cannot use slash arts until repaired. */
    public boolean broken(Blade b) {
        return wear >= b.durability(this);
    }

    /** Wears the blade by {@code amount}; true when this broke it. */
    public boolean wear(Blade b, int amount) {
        boolean was = broken(b);
        wear = Math.min(b.durability(this), wear + Math.max(0, amount));
        return !was && broken(b);
    }

    /** Proud soul a full repair costs: one per 10 wear, rounded up. */
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
        return new BladeStats(xp, kills, proudSoul, enhancement, star, wear, maker);
    }
}
