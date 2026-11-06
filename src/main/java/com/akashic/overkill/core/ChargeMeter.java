package com.akashic.overkill.core;

/**
 * Holding to charge a slash art (the sword raised, right mouse held): {@link #hold} every tick it is held,
 * {@link #release} when it no longer is. Main thread only.
 */
public final class ChargeMeter {

    private long since = Long.MIN_VALUE;

    /** The player holds at {@code now} (starts counting on the first tick). */
    public void hold(long now) {
        if (since == Long.MIN_VALUE) {
            since = now;
        }
    }

    public boolean holding() {
        return since != Long.MIN_VALUE;
    }

    /** Ticks held so far (0 when not holding). */
    public long held(long now) {
        return holding() ? now - since : 0;
    }

    /** 0..1 toward {@code needed} ticks. */
    public float progress(long now, int needed) {
        return needed <= 0 ? 1 : (float) Math.min(1, held(now) / (double) needed);
    }

    /**
     * Let go at {@code now}.
     *
     * @return whether it had been held for {@code needed} ticks
     */
    public boolean release(long now, int needed) {
        boolean charged = holding() && now - since >= needed;
        since = Long.MIN_VALUE;
        return charged;
    }

    public void cancel() {
        since = Long.MIN_VALUE;
    }
}
