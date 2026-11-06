package com.akashic.overkill.core;

import java.util.ArrayDeque;

/**
 * The style rank of one player, D to SSS: hits raise it, varied moves more than the same one over and over, getting
 * hit drops it hard, and it drains when the player stops fighting, faster the higher it is. Counted in server ticks.
 * Main thread only.
 */
public final class StyleRank {

    public static final String[] NAMES = { "D", "C", "B", "A", "S", "SS", "SSS" };
    /** Points at which each rank starts. */
    static final int[] THRESHOLDS = { 1, 100, 250, 450, 700, 1000, 1400 };
    public static final int MAX = 1800;
    /** Ticks without a hit before the rank drains. */
    public static final int GRACE = 50;
    private static final int RECENT = 6;

    private double points;
    private long lastGain = Long.MIN_VALUE / 2;
    private final ArrayDeque<String> recent = new ArrayDeque<>();

    /**
     * A target hit by {@code move} worth {@code base} points: full for a move not used lately, halved for each recent
     * use of the same move.
     *
     * @return the points gained
     */
    public double hit(String move, int base, long now) {
        int repeats = 0;
        for (String r : recent) {
            if (r.equals(move)) {
                repeats++;
            }
        }
        double gain = base / Math.pow(2, repeats);
        points = Math.min(MAX, points + gain);
        lastGain = now;
        recent.addLast(move);
        if (recent.size() > RECENT) {
            recent.removeFirst();
        }
        return gain;
    }

    /** Bonus points for a stylish action that is not a hit (a dodge just in time, a taunt). */
    public void bonus(int base, long now) {
        points = Math.min(MAX, points + base);
        lastGain = now;
    }

    /** The player took {@code damage}: a hard drop. */
    public void hurt(double damage) {
        points = Math.max(0, points - 80 - damage * 20);
    }

    /** Once per tick: drains after {@link #GRACE} ticks without a gain. */
    public void tick(long now) {
        if (points > 0 && now - lastGain > GRACE) {
            points = Math.max(0, points - (0.6 + 0.5 * Math.max(0, rank())));
        }
    }

    public void reset() {
        points = 0;
        recent.clear();
    }

    public double points() {
        return points;
    }

    /** 0 (D) to 6 (SSS), or -1 with no points at all. */
    public int rank() {
        for (int i = THRESHOLDS.length - 1; i >= 0; i--) {
            if (points >= THRESHOLDS[i]) {
                return i;
            }
        }
        return -1;
    }

    public String name() {
        int r = rank();
        return r < 0 ? "" : NAMES[r];
    }

    /** 0..1 through the current rank (1 at SSS's cap). */
    public float progress() {
        int r = rank();
        if (r < 0) {
            return 0;
        }
        double from = THRESHOLDS[r];
        double to = r + 1 < THRESHOLDS.length ? THRESHOLDS[r + 1] : MAX;
        return (float) Math.max(0, Math.min(1, (points - from) / (to - from)));
    }
}
