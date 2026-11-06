package com.akashic.overkill.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One attack of a combo, in server ticks from the instant it starts. Its id is also the animation state the resource
 * pack plays ({@code estados} of {@code item_models_3d.json}). Immutable; built with {@link #builder}.
 *
 * <pre>
 * 0 ......... startup ......... startup+active ......... total
 *  wind-up       hits (each tick)        recovery
 *                    cancelFrom ............ cancelTo     an attack here chains into next
 * </pre>
 */
public final class Move {

    /** The animation state names Kunftig accepts. */
    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");

    public final String id;
    /** When this move may start (as an entry or a follow-up). */
    public final Condition when;
    public final int startup;
    public final int active;
    public final int recovery;
    /** Window (ticks from the start, inclusive) in which an attack chains into {@link #next}. */
    public final int cancelFrom;
    public final int cancelTo;
    /** Times the blade's attack damage. */
    public final double damage;
    /** Blocks from the attacker's eyes. */
    public final double reach;
    /** Width of the swing, degrees, centred on the look (360: all around). */
    public final double arc;
    /** Horizontal push given to targets, blocks per tick. */
    public final double push;
    /** Vertical speed given to targets (a launcher), blocks per tick; 0 leaves them. */
    public final double launch;
    /** Forward speed given to the attacker at the start (a lunge), blocks per tick. */
    public final double lunge;
    /** Vertical speed given to the attacker at the start (negative: a plunge), blocks per tick; NaN leaves it. */
    public final double lift;
    /** Style points per target hit. */
    public final int style;
    /** The moves an attack in the cancel window can chain into, first match wins. */
    public final List<String> next;

    private Move(Builder b) {
        id = b.id;
        when = b.when;
        startup = b.startup;
        active = b.active;
        recovery = b.recovery;
        cancelFrom = b.cancelFrom < 0 ? startup + active : b.cancelFrom;
        cancelTo = b.cancelTo < 0 ? startup + active + recovery - 1 : b.cancelTo;
        damage = b.damage;
        reach = b.reach;
        arc = b.arc;
        push = b.push;
        launch = b.launch;
        lunge = b.lunge;
        lift = b.lift;
        style = b.style;
        next = Collections.unmodifiableList(new ArrayList<>(b.next));
    }

    /** Ticks from the start to the end of the recovery. */
    public int total() {
        return startup + active + recovery;
    }

    /** Whether {@code elapsed} ticks into the move are a hit tick. */
    public boolean hitsAt(long elapsed) {
        return elapsed >= startup && elapsed < startup + active;
    }

    /** Whether an attack {@code elapsed} ticks into the move chains. */
    public boolean chainsAt(long elapsed) {
        return elapsed >= cancelFrom && elapsed <= cancelTo;
    }

    public static Builder builder(String id) {
        if (id == null || !ID.matcher(id)
            .matches()) {
            throw new IllegalArgumentException("move id must match " + ID + ": " + id);
        }
        return new Builder(id);
    }

    @Override
    public String toString() {
        return id;
    }

    /** Builds a {@link Move}; frames default to a quick slash. */
    public static final class Builder {

        private final String id;
        private Condition when = Condition.ANY;
        private int startup = 2;
        private int active = 2;
        private int recovery = 8;
        private int cancelFrom = -1;
        private int cancelTo = -1;
        private double damage = 1;
        private double reach = 3.5;
        private double arc = 110;
        private double push = 0.25;
        private double launch;
        private double lunge;
        private double lift = Double.NaN;
        private int style = 10;
        private final List<String> next = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder when(Condition c) {
            when = c;
            return this;
        }

        /** Wind-up, hit ticks and recovery. */
        public Builder frames(int startupTicks, int activeTicks, int recoveryTicks) {
            if (startupTicks < 0 || activeTicks < 1 || recoveryTicks < 0) {
                throw new IllegalArgumentException(id + ": frames must be >= 0, >= 1, >= 0");
            }
            startup = startupTicks;
            active = activeTicks;
            recovery = recoveryTicks;
            return this;
        }

        /** The chain window; by default from the end of the hits to the end of the move. */
        public Builder cancel(int from, int to) {
            cancelFrom = from;
            cancelTo = to;
            return this;
        }

        public Builder damage(double multiplier) {
            damage = multiplier;
            return this;
        }

        public Builder area(double reachBlocks, double arcDegrees) {
            reach = reachBlocks;
            arc = arcDegrees;
            return this;
        }

        public Builder push(double speed) {
            push = speed;
            return this;
        }

        public Builder launch(double speed) {
            launch = speed;
            return this;
        }

        public Builder lunge(double speed) {
            lunge = speed;
            return this;
        }

        public Builder lift(double speed) {
            lift = speed;
            return this;
        }

        public Builder style(int points) {
            style = points;
            return this;
        }

        public Builder next(String... ids) {
            for (String n : ids) {
                next.add(n);
            }
            return this;
        }

        public Move build() {
            Move m = new Move(this);
            if (m.cancelFrom > m.cancelTo || m.cancelTo >= m.total()) {
                throw new IllegalArgumentException(id + ": cancel window must lie within the move");
            }
            return m;
        }
    }
}
