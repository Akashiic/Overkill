package com.akashic.overkill.combo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One node of the combo graph, SlashBlade's {@code ComboState} (Resharped, MIT): a span of the blade motion (frames at
 * 30 fps), how long it lasts, how strong it is (lower priority number wins), where a click and a timeout go, and what
 * it does: on entering ({@code click}), on given ticks ({@code timeline}: key k = the (k+1)-th tick of the state),
 * every tick ({@code tick} scripts, with the raw elapsed ticks), while the use button is held ({@code hold}), the
 * stun its hits give, the air hover, and the body clip the player model plays.
 */
public final class ComboState {

    /** The hover a state gives while airborne. */
    public enum Hover {
        NONE,
        /** {@code fallDecrease}: weakening with each airborne tick. */
        DECREASE,
        /** {@code fallResist}: strong. */
        RESIST
    }

    /** What the player model plays from this state on (SlashBlade's {@code PlayerAnimationOverrider}). */
    public static final class Clip {

        public final int start;
        public final int end;
        public final boolean loop;
        public final boolean blendLegs;

        public Clip(int start, int end, boolean loop, boolean blendLegs) {
            this.start = start;
            this.end = end;
            this.loop = loop;
            this.blendLegs = blendLegs;
        }
    }

    /** What actions and scripts get to see. */
    public static final class Ctx {

        public final ComboMachine machine;
        public final Actor actor;
        public final ComboState state;
        public final long now;
        /** Ticks since the state was entered ({@code gameTime - lastActionTime}). */
        public final long elapsed;
        /** Ticks of this state processed before this one (0 on its first tick). */
        public final int stateTicks;
        /** Held keys, ground or air and, for a click, the click. */
        public final int inputs;

        Ctx(ComboMachine machine, Actor actor, ComboState state, long now, long elapsed, int stateTicks, int inputs) {
            this.machine = machine;
            this.actor = actor;
            this.state = state;
            this.now = now;
            this.elapsed = elapsed;
            this.stateTicks = stateTicks;
            this.inputs = inputs;
        }
    }

    /** Something a state does. */
    @FunctionalInterface
    public interface Action {

        void run(Ctx c);
    }

    public final String id;
    public final int start;
    public final int end;
    public final float speed;
    public final boolean loop;
    /** Extra milliseconds after the motion. */
    public final int timeout;
    public final int priority;
    public final boolean aerial;
    public final Next next;
    public final String nextOfTimeout;
    public final List<Action> click;
    public final Map<Integer, List<Action>> timeline;
    public final List<Action> tick;
    public final Action hold;
    public final boolean quickCharge;
    /** Stun ticks of a hit made in this state; 0: no stun. */
    public final int stun;
    public final Hover hover;
    public final Clip clip;

    private ComboState(Builder b) {
        id = b.id;
        start = b.start;
        end = b.end;
        speed = b.speed;
        loop = b.loop;
        timeout = b.timeout;
        priority = b.priority;
        aerial = b.aerial;
        next = b.next;
        nextOfTimeout = b.nextOfTimeout;
        click = Collections.unmodifiableList(new ArrayList<>(b.click));
        Map<Integer, List<Action>> tl = new HashMap<>();
        for (Map.Entry<Integer, List<Action>> e : b.timeline.entrySet()) {
            tl.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        timeline = Collections.unmodifiableMap(tl);
        tick = Collections.unmodifiableList(new ArrayList<>(b.tick));
        hold = b.hold;
        quickCharge = b.quickCharge;
        stun = b.stun;
        hover = b.hover;
        clip = b.clip;
    }

    /** SlashBlade's {@code getTimeoutMS}: {@code (int)(frames * 1000/30 / speed) + timeout}. */
    public int timeoutMs() {
        return (int) (Math.abs(end - start) * (1000.0 / 30.0) / speed) + timeout;
    }

    /** The first elapsed tick at which the state is over ({@code elapsed * 50 > timeoutMs}). */
    public int expiresAt() {
        return timeoutMs() / 50 + 1;
    }

    public boolean isNone() {
        return Combos.NONE.equals(id);
    }

    @Override
    public String toString() {
        return id;
    }

    public static Builder of(String id, int start, int end) {
        return new Builder(id, start, end);
    }

    /** Frames to ticks as SlashBlade truncates them ({@code (int)(f * 20/30)}). */
    public static int ticks(int frames) {
        return (int) (frames * 20.0 / 30.0);
    }

    public static final class Builder {

        private final String id;
        private final int start;
        private final int end;
        private float speed = 1;
        private boolean loop;
        private int timeout;
        private int priority = 1000;
        private boolean aerial;
        private Next next = Next.to(Combos.NONE);
        private String nextOfTimeout = Combos.NONE;
        private final List<Action> click = new ArrayList<>();
        private final Map<Integer, List<Action>> timeline = new HashMap<>();
        private final List<Action> tick = new ArrayList<>();
        private Action hold;
        private boolean quickCharge;
        private int stun;
        private Hover hover = Hover.NONE;
        private Clip clip;

        private Builder(String id, int start, int end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public Builder speed(float v) {
            speed = v;
            return this;
        }

        public Builder loop() {
            loop = true;
            return this;
        }

        public Builder timeout(int ms) {
            timeout = ms;
            return this;
        }

        public Builder priority(int p) {
            priority = p;
            return this;
        }

        public Builder aerial() {
            aerial = true;
            return this;
        }

        public Builder next(Next n) {
            next = n;
            return this;
        }

        public Builder next(String id) {
            next = Next.to(id);
            return this;
        }

        public Builder timeoutTo(String id) {
            nextOfTimeout = id;
            return this;
        }

        public Builder click(Action a) {
            click.add(a);
            return this;
        }

        /** An action at timeline key {@code tick}. */
        public Builder at(int tick, Action a) {
            timeline.computeIfAbsent(tick, k -> new ArrayList<>())
                .add(a);
            return this;
        }

        /** An action at the timeline key of frame {@code frame} (SlashBlade's {@code (int) F(frame)}). */
        public Builder atFrame(int frame, Action a) {
            return at(ticks(frame), a);
        }

        public Builder tick(Action a) {
            tick.add(a);
            return this;
        }

        public Builder hold(Action a) {
            hold = a;
            return this;
        }

        public Builder quickCharge() {
            quickCharge = true;
            return this;
        }

        /** The sheathing click on the first tick (SlashBlade's {@code playQuickSheathSoundAction} at key 0). */
        public Builder sheath() {
            return at(0, c -> c.actor.sheathSound());
        }

        public Builder stun(int ticks) {
            stun = ticks;
            return this;
        }

        /** The default hit effect: a 10-tick stun. */
        public Builder stun() {
            return stun(10);
        }

        public Builder hover(Hover h) {
            hover = h;
            return this;
        }

        /** The cosmetic body spin: {@code degrees} per tick on keys from..to-1, reset at key to. */
        public Builder spin(int from, int to, float degrees) {
            for (int t = from; t < to; t++) {
                at(t, c -> c.actor.spin(degrees));
            }
            return at(to, c -> c.actor.spin(0));
        }

        public Builder clip(int start, int end, boolean loop, boolean blendLegs) {
            clip = new Clip(start, end, loop, blendLegs);
            return this;
        }

        public ComboState build() {
            return new ComboState(this);
        }
    }
}
