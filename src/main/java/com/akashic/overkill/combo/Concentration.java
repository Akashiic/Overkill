package com.akashic.overkill.combo;

/**
 * SlashBlade's concentration rank on the server ({@code IConcentrationRank}, Resharped, MIT): 300 points per level up
 * to 1799; decaying one point per tick since the last change but never below the floor of the level they are in (so
 * D..A keep their rank and S, SS, SSS fall back to S); +30 per hit dealt with a blade, -300 per hurt taken. Times are
 * server ticks. Not thread-safe.
 */
public final class Concentration {

    public static final int UNIT = 300;
    public static final int MAX = 6 * UNIT - 1;
    /** A hit dealt: 0.1 of a level. */
    public static final int HIT = 30;
    /** A hurt taken: a whole level. */
    public static final int HURT = -300;

    private long raw;
    private long lastUpdate;

    public long points(long now) {
        long reduction = Math.max(0, now - lastUpdate);
        return raw - Math.min(reduction, raw % UNIT);
    }

    /** The level as a float (0 .. 5.997), what effects carry. */
    public float level(long now) {
        return points(now) / (float) UNIT;
    }

    /** 0 none, 1 D, 2 C, 3 B, 4 A, 5 S, 6 SS, 7 SSS. */
    public int rank(long now) {
        float l = level(now);
        if (l < 1) {
            return 0;
        }
        if (l < 5) {
            return (int) l;
        }
        if (l < 5.25f) {
            return 5;
        }
        return l < 5.5f ? 6 : 7;
    }

    /** {@code addRankPoint}: the decayed points plus {@code delta}, clamped. Returns the new points. */
    public long add(long delta, long now) {
        raw = Math.max(0, Math.min(MAX, points(now) + delta));
        lastUpdate = now;
        return raw;
    }

    /** SlashBlade's damage bonus: rank / 2 (D 0.5 ... SSS 3.5). */
    public double damageBonus(long now) {
        return rank(now) / 2.0;
    }

    public void reset() {
        raw = 0;
        lastUpdate = 0;
    }
}
