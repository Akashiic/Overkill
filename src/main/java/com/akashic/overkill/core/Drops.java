package com.akashic.overkill.core;

import java.util.Random;

/**
 * What a kill with a katana may drop besides the mob's own loot: a whetstone, a talisman, a tamahagane. The tier is
 * the victim's: by max health (up to 20 common, 40 uncommon, 80 rare, 150 epic, 300 legendary, more mythic; bosses
 * mythic), as the AkashicFishing fish bring the stone of their own tier. Immutable.
 */
public final class Drops {

    /** One kill's extras; any may be null. */
    public static final class Roll {

        public final StoneTier stone;
        public final SealTier seal;
        public final boolean tamahagane;

        Roll(StoneTier stone, SealTier seal, boolean tamahagane) {
            this.stone = stone;
            this.seal = seal;
            this.tamahagane = tamahagane;
        }

        public boolean any() {
            return stone != null || seal != null || tamahagane;
        }
    }

    public static final Drops DEFAULT = new Drops(0.0025, 0.004, 0.005, 0.03);

    private final double stoneChance;
    private final double sealChance;
    private final double tamahaganeLegendary;
    private final double tamahaganeMythic;

    public Drops(double stoneChance, double sealChance, double tamahaganeLegendary, double tamahaganeMythic) {
        for (double c : new double[] { stoneChance, sealChance, tamahaganeLegendary, tamahaganeMythic }) {
            if (!(c >= 0 && c <= 1)) {
                throw new IllegalArgumentException("drop chance out of 0..1: " + c);
            }
        }
        this.stoneChance = stoneChance;
        this.sealChance = sealChance;
        this.tamahaganeLegendary = tamahaganeLegendary;
        this.tamahaganeMythic = tamahaganeMythic;
    }

    /** The victim's tier. */
    public static StoneTier tier(double maxHealth, boolean boss) {
        if (boss || maxHealth > 300) {
            return StoneTier.MYTHIC;
        }
        if (maxHealth > 150) {
            return StoneTier.LEGENDARY;
        }
        if (maxHealth > 80) {
            return StoneTier.EPIC;
        }
        if (maxHealth > 40) {
            return StoneTier.RARE;
        }
        return maxHealth > 20 ? StoneTier.UNCOMMON : StoneTier.COMMON;
    }

    /** The talisman of each tier: 25% common and uncommon, 50% rare, 75% epic, 100% legendary and mythic. */
    public static SealTier seal(StoneTier tier) {
        switch (tier) {
            case RARE:
                return SealTier.P50;
            case EPIC:
                return SealTier.P75;
            case LEGENDARY:
            case MYTHIC:
                return SealTier.P100;
            default:
                return SealTier.P25;
        }
    }

    public Roll roll(double maxHealth, boolean boss, Random random) {
        StoneTier tier = tier(maxHealth, boss);
        StoneTier stone = random.nextDouble() < stoneChance ? tier : null;
        SealTier seal = random.nextDouble() < sealChance ? seal(tier) : null;
        double t = tier == StoneTier.MYTHIC ? tamahaganeMythic : tier == StoneTier.LEGENDARY ? tamahaganeLegendary : 0;
        boolean tama = t > 0 && random.nextDouble() < t;
        return new Roll(stone, seal, tama);
    }
}
