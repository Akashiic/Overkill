package com.akashic.overkill.core;

/**
 * One player's combo, on the server, counted in server ticks. An attack from idle starts an entry (a quick-draw while
 * the blade is being drawn); an attack in the running move's cancel window chains into its follow-up; any other attack
 * while a move runs is ignored (no spam). The client predicts the same chain from the pack's {@code ao_atacar}, so
 * the rules here and there must agree: both count from the start of the move. Main thread only.
 */
public final class ComboMachine {

    private final Combo combo;
    private Move current;
    private long start;
    private long drawUntil = Long.MIN_VALUE;
    private int chain;

    public ComboMachine(Combo combo) {
        this.combo = combo;
    }

    public Combo combo() {
        return combo;
    }

    /** The blade just came to the hand at {@code now}: an attack in the next {@link Combo#drawTicks} is a quick-draw. */
    public void draw(long now) {
        reset();
        drawUntil = now + combo.drawTicks;
    }

    /** Back to idle (the blade left the hand, the player was stunned...). */
    public void reset() {
        current = null;
        drawUntil = Long.MIN_VALUE;
        chain = 0;
    }

    /**
     * An attack at {@code now}.
     *
     * @return the move it starts, or null when it starts nothing
     */
    public Move attack(long now, Stance stance) {
        Move next;
        if (running(now)) {
            if (!current.chainsAt(now - start)) {
                return null;
            }
            next = combo.next(current, stance);
        } else {
            next = combo.entry(stance, now < drawUntil);
            chain = 0;
        }
        if (next == null) {
            return null;
        }
        current = next;
        start = now;
        drawUntil = Long.MIN_VALUE;
        chain++;
        return next;
    }

    /** Whether a move runs at {@code now}. */
    public boolean running(long now) {
        return current != null && now - start < current.total();
    }

    /** The move running at {@code now}, or null. */
    public Move current(long now) {
        return running(now) ? current : null;
    }

    /** Ticks since the running move started. */
    public long elapsed(long now) {
        return now - start;
    }

    /** Whether {@code now} is a hit tick of the running move. */
    public boolean hitting(long now) {
        return running(now) && current.hitsAt(now - start);
    }

    /** Moves chained so far in the running combo (1 for an entry). */
    public int chain() {
        return chain;
    }
}
