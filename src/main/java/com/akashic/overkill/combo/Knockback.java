package com.akashic.overkill.combo;

/**
 * SlashBlade's {@code KnockBacks}: what a hit does to the target's motion, as (horizontal factor, vertical, fall
 * distance added). All but {@link #SMASH} cancel the horizontal knockback and zero the horizontal motion.
 */
public enum Knockback {

    /** Stays in place. */
    CANCEL(0, 0, 0),
    /** Thrown up: vy = max(vy, 0.75). */
    TOSS(0, 0.75, -0.75),
    /** Slammed down: vy = min(vy, -5.5). */
    METEOR(0, -5.5, 5.5),
    /** Vanilla knockback x1.5. */
    SMASH(1.5, 0, 0);

    public final double horizontal;
    public final double vertical;
    public final double fall;

    Knockback(double horizontal, double vertical, double fall) {
        this.horizontal = horizontal;
        this.vertical = vertical;
        this.fall = fall;
    }
}
