package com.akashic.overkill.core;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The forge: fortifies a katana one level at a time, +1 to +{@link #MAX_PLUS} and then the Ascension, the same ladder
 * as the AkashicFishing rods' Upgrader. Each try costs experience levels and needs the katana at the step's level; a
 * roll under the chance goes up one level. From +5 the chance falls geometrically, {@code 0.5 * 0.653^(n - 5)} (50% at
 * +5, 0.3% at +17), and a failure <b>regresses</b> the katana one level with a chance equal to the step's (so every
 * level gained costs, on average, one fall). A whetstone multiplies the chance by its tier (up to
 * {@link #maxChance()}); a talisman holds a regression back with its own chance. The Ascension takes a tamahagane in the
 * stone slot instead and never regresses. Immutable.
 */
public final class Forge {

    public static final int MAX_PLUS = 17;
    /** The level after +17. */
    public static final int ASCENSION = MAX_PLUS + 1;

    /** One step: to {@code target}, needs katana level {@code minLevel}, costs {@code cost} XP levels. */
    public static final class Step {

        public final int target;
        public final int minLevel;
        public final int cost;
        public final double chance;
        public final double regression;

        public Step(int target, int minLevel, int cost, double chance, double regression) {
            if (target < 1 || target > ASCENSION || minLevel < 1 || cost < 0 || !(chance > 0 && chance <= 1)
                || !(regression >= 0 && regression <= 1)) {
                throw new IllegalArgumentException("bad forge step to +" + target);
            }
            this.target = target;
            this.minLevel = minLevel;
            this.cost = cost;
            this.chance = chance;
            this.regression = regression;
        }

        public boolean ascension() {
            return target == ASCENSION;
        }
    }

    public static final List<Step> DEFAULT_STEPS = Arrays.asList(
        new Step(1, 1, 1, 1.00, 0.00),
        new Step(2, 1, 1, 0.95, 0.00),
        new Step(3, 1, 2, 0.90, 0.00),
        new Step(4, 1, 2, 0.85, 0.00),
        new Step(5, 1, 3, 0.50, 0.50),
        new Step(6, 20, 3, 0.3264, 0.3264),
        new Step(7, 20, 4, 0.2131, 0.2131),
        new Step(8, 20, 5, 0.1392, 0.1392),
        new Step(9, 20, 6, 0.0909, 0.0909),
        new Step(10, 20, 7, 0.0593, 0.0593),
        new Step(11, 50, 8, 0.0387, 0.0387),
        new Step(12, 50, 10, 0.0253, 0.0253),
        new Step(13, 50, 12, 0.0165, 0.0165),
        new Step(14, 50, 14, 0.0108, 0.0108),
        new Step(15, 50, 16, 0.0070, 0.0070),
        new Step(16, 75, 20, 0.0046, 0.0046),
        new Step(17, 75, 25, 0.0030, 0.0030),
        new Step(18, 99, 40, 0.10, 0.0));

    public static final Forge DEFAULT = new Forge(DEFAULT_STEPS, 0.95);

    public enum Result {
        UPGRADED, FAILED, REGRESSED, MAXED, LEVEL_TOO_LOW, CANNOT_PAY, NEEDS_TAMAHAGANE
    }

    /**
     * A try's outcome: the level after it, the step, the chance used, the roll, and whether the talisman held a
     * regression back. Chance and roll are 0 when the try did not happen.
     */
    public static final class Outcome {

        public final Result result;
        public final int enhancement;
        public final Step step;
        public final double chance;
        public final double roll;
        public final boolean sealSaved;

        Outcome(Result result, int enhancement, Step step, double chance, double roll, boolean sealSaved) {
            this.result = result;
            this.enhancement = enhancement;
            this.step = step;
            this.chance = chance;
            this.roll = roll;
            this.sealSaved = sealSaved;
        }

        /** Whether the try happened (the price was paid, the stone spent). */
        public boolean tried() {
            return result == Result.UPGRADED || result == Result.FAILED || result == Result.REGRESSED;
        }
    }

    private final Step[] steps;
    private final int max;
    private final double maxChance;

    public Forge(List<Step> steps, double maxChance) {
        if (!(maxChance > 0 && maxChance <= 1)) {
            throw new IllegalArgumentException("max chance out of 0..1");
        }
        int top = 0;
        for (Step s : steps) {
            top = Math.max(top, s.target);
        }
        if (top == 0) {
            throw new IllegalArgumentException("no forge steps");
        }
        this.steps = new Step[top + 1];
        for (Step s : steps) {
            this.steps[s.target] = s;
        }
        for (int t = 1; t <= top; t++) {
            if (this.steps[t] == null) {
                throw new IllegalArgumentException("missing forge step to +" + t);
            }
        }
        this.max = top;
        this.maxChance = maxChance;
    }

    public int max() {
        return max;
    }

    public double maxChance() {
        return maxChance;
    }

    /** The step after {@code enhancement}, or null at the top. */
    public Step next(int enhancement) {
        return enhancement >= max ? null : steps[Math.max(0, enhancement) + 1];
    }

    public Step step(int target) {
        return steps[target];
    }

    /**
     * The step's chance with a whetstone of this tier (null: none), capped at {@link #maxChance()} (a chance already
     * above the cap stays). The Ascension takes no whetstone.
     */
    public double chance(Step step, StoneTier stone) {
        if (stone == null || step.ascension()) {
            return step.chance;
        }
        return Math.max(step.chance, Math.min(maxChance, step.chance * stone.multiplier()));
    }

    /** The chance a failure regresses a katana at {@code enhancement}, with a talisman (null: none); 0 at +0. */
    public double regression(Step step, int enhancement, SealTier seal) {
        if (enhancement == 0) {
            return 0;
        }
        return step.regression * (1 - (seal == null ? 0 : seal.protection()));
    }

    /**
     * Tries the level after {@code enhancement} for a katana of level {@code katanaLevel}; {@code levels} is what the
     * player can pay, {@code stone} the whetstone (null: none), {@code tamahagane} whether a tamahagane is in the stone
     * slot.
     */
    public Outcome upgrade(int enhancement, int katanaLevel, int levels, StoneTier stone, boolean tamahagane,
        SealTier seal, Random random) {
        Step step = next(enhancement);
        if (step == null) {
            return new Outcome(Result.MAXED, enhancement, null, 0, 0, false);
        }
        if (katanaLevel < step.minLevel) {
            return new Outcome(Result.LEVEL_TOO_LOW, enhancement, step, 0, 0, false);
        }
        if (step.ascension() && !tamahagane) {
            return new Outcome(Result.NEEDS_TAMAHAGANE, enhancement, step, 0, 0, false);
        }
        if (levels < step.cost) {
            return new Outcome(Result.CANNOT_PAY, enhancement, step, 0, 0, false);
        }
        double chance = chance(step, stone);
        double roll = random.nextDouble();
        if (roll < chance) {
            return new Outcome(Result.UPGRADED, step.target, step, chance, roll, false);
        }
        if (enhancement > 0 && random.nextDouble() < step.regression) {
            if (seal != null && random.nextDouble() < seal.protection()) {
                return new Outcome(Result.FAILED, enhancement, step, chance, roll, true);
            }
            return new Outcome(Result.REGRESSED, enhancement - 1, step, chance, roll, false);
        }
        return new Outcome(Result.FAILED, enhancement, step, chance, roll, false);
    }

    /**
     * Expected tries from +{@code from} to +{@code to} with this whetstone and talisman on every try (null: none),
     * exact by first passage: {@code T(n) = (1 + (1 - q) * r * T(n-1)) / q}.
     */
    public double expectedTries(int from, int to, StoneTier stone, SealTier seal) {
        double[] t = new double[max + 1];
        double total = 0;
        for (int n = 1; n <= Math.min(to, max); n++) {
            Step s = steps[n];
            double q = chance(s, stone);
            double r = n == 1 ? 0 : s.regression * (1 - (seal == null ? 0 : seal.protection()));
            t[n] = (1 + (1 - q) * r * t[n - 1]) / q;
            if (n > from) {
                total += t[n];
            }
        }
        return total;
    }

    /** What the fortification multiplies the katana's attack and durability by: 1 + 0.04 per level, +0.5 ascended. */
    public static double multiplier(int enhancement) {
        int n = Math.max(0, Math.min(enhancement, MAX_PLUS));
        return 1 + 0.04 * n + (enhancement >= ASCENSION ? 0.5 : 0);
    }

    /** "+7", or "ASC". */
    public static String label(int enhancement) {
        return enhancement >= ASCENSION ? "ASC" : "+" + enhancement;
    }
}
