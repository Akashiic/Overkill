package com.akashic.overkill.combo;

import java.util.List;

/**
 * One fighter's combo state, SlashBlade's {@code ISlashBladeState} engine (Resharped, MIT): the current state and the
 * tick it was entered, timeouts resolved one per tick, clicks resolved against the root command table by priority,
 * hold and release (charge, quick charge, slash arts), and landing. Pure: the world is the {@link Actor}; the
 * {@link Listener} hears every state change (to play the motion, for example).
 *
 * <p>SlashBlade drops a click that comes inside a strike's lock window. With a click buffer ({@link #bufferTicks}),
 * as in Devil May Cry, that click is kept instead and goes through on the first tick the strike takes it, as long as
 * it is no older than the buffer: an early click still lands the next strike at its earliest moment, so the strings
 * flow with lag and frame drops. The held keys are the ones of the click; ground or air, the current ones. A new
 * click replaces a kept one; a slash art, a landing or a reset forgets it. 0 is SlashBlade's own behaviour.
 */
public final class ComboMachine {

    /** Why a state was entered. */
    public enum Cause {
        CLICK,
        TIMEOUT,
        LAND,
        HOLD,
        SCRIPT,
        CHARGE,
        RESET
    }

    /** Hears state changes. */
    @FunctionalInterface
    public interface Listener {

        void entered(ComboState state, Cause cause, long now);
    }

    private final Combos combos;
    private final Listener listener;
    private ComboState state;
    private long lastAction;
    private int stateTicks;
    private boolean ticking;
    private int bufferTicks;
    private int bufferedInputs;
    private long bufferedAt = -1;

    public ComboMachine(Combos combos, Listener listener) {
        this.combos = combos;
        this.listener = listener == null ? (s, c, n) -> {} : listener;
        this.state = combos.get(Combos.STANDBY);
    }

    public Combos combos() {
        return combos;
    }

    /** How long (ticks) a click that came inside a lock window is kept; 0 drops it, as SlashBlade does. */
    public void bufferTicks(int ticks) {
        bufferTicks = Math.max(0, ticks);
    }

    public int bufferTicks() {
        return bufferTicks;
    }

    /** Whether a click is being kept for the next strike. */
    public boolean hasBufferedClick() {
        return bufferedAt >= 0;
    }

    public ComboState state() {
        return state;
    }

    public long lastAction() {
        return lastAction;
    }

    /** Ticks since the current state was entered. */
    public long elapsed(long now) {
        return Math.max(0, now - lastAction);
    }

    private ComboState.Ctx ctx(Actor a, long now, int inputs) {
        return new ComboState.Ctx(this, a, state, now, elapsed(now), stateTicks, inputs);
    }

    /** {@code updateComboSeq}: enters {@code id} now and runs its click actions. */
    public void enter(String id, long now, Actor a, Cause cause) {
        ComboState s = combos.get(id);
        state = s;
        lastAction = now;
        stateTicks = 0;
        if (cause == Cause.CHARGE || cause == Cause.LAND || cause == Cause.RESET) {
            bufferedAt = -1;
        }
        listener.entered(s, cause, now);
        ComboState.Ctx c = ctx(a, now, a.inputs());
        for (ComboState.Action act : s.click) {
            act.run(c);
            if (state != s) {
                return; // an action moved on already
            }
        }
    }

    /** {@code resolvCurrentComboStateTicks}: at most one timeout transition per call. */
    public ComboState resolve(long now, Actor a) {
        if (!state.isNone() && elapsed(now) * 50 > state.timeoutMs()) {
            enter(state.nextOfTimeout, now, a, Cause.TIMEOUT);
        }
        return state;
    }

    /** One server tick with the blade in the main hand: timeouts, the timeline, the scripts, the hover. */
    public void tick(long now, Actor a) {
        if (ticking) {
            return;
        }
        ticking = true;
        try {
            resolve(now, a);
            if (bufferedAt >= 0) {
                if (now - bufferedAt > bufferTicks) {
                    bufferedAt = -1;
                } else {
                    int ground = a.onGround() ? Input.ON_GROUND : Input.ON_AIR;
                    int inputs = bufferedInputs & ~(Input.ON_GROUND | Input.ON_AIR) | ground;
                    if (accept(now, a, inputs) != null) {
                        bufferedAt = -1;
                    }
                }
            }
            ComboState s = state;
            ComboState.Ctx c = ctx(a, now, a.inputs());
            List<ComboState.Action> keyed = s.timeline.get(stateTicks);
            if (keyed != null) {
                for (ComboState.Action act : keyed) {
                    act.run(c);
                }
            }
            for (ComboState.Action act : s.tick) {
                act.run(c);
            }
            if (s.hover == ComboState.Hover.DECREASE) {
                a.fallDecrease();
            } else if (s.hover == ComboState.Hover.RESIST) {
                a.fallResist();
            }
            if (state == s) {
                stateTicks++;
            }
        } finally {
            ticking = false;
        }
    }

    /**
     * {@code progressCombo}: a right click ({@link Input#R_CLICK}) or a left click on an entity ({@link Input#L_CLICK})
     * with these held {@code inputs}. Returns the state entered, or null when the click was ignored.
     */
    public String click(long now, Actor a, int inputs) {
        String entered = accept(now, a, inputs);
        if (entered == null && bufferTicks > 0) {
            bufferedInputs = inputs;
            bufferedAt = now;
        } else {
            bufferedAt = -1;
        }
        return entered;
    }

    /** The click itself: the state entered, or null when the current state does not take it now. */
    private String accept(long now, Actor a, int inputs) {
        ComboState cur = resolve(now, a);
        ComboState.Ctx c = ctx(a, now, inputs);
        String next = cur.next.apply(c);
        if (!Combos.NONE.equals(next) && next.equals(cur.id)) {
            return null; // a lock window, or a state clicks cannot interrupt
        }
        String root = combos.rootCommand(inputs);
        String resolved = combos.get(next).priority <= combos.get(root).priority ? next : root;
        enter(resolved, now, a, Cause.CLICK);
        return resolved;
    }

    /** {@code holdAction}: every tick while the use button is held, on the current (not resolved) state. */
    public void hold(long now, Actor a) {
        if (state.hold != null) {
            state.hold.run(ctx(a, now, a.inputs()));
        }
    }

    /**
     * {@code doChargeAction}: the use button released after {@code heldTicks}; starts the art's state when the charge,
     * or a quick charge (releasing within 3 + Soul Speed ticks of a sheathing state), allows it. Returns the state
     * entered, or null.
     */
    public String release(long now, Actor a, int heldTicks, SlashArt art, int soulSpeed) {
        if (heldTicks <= 2 || art == null) {
            return null;
        }
        ComboState cur = resolve(now, a);
        ComboState.Ctx c = ctx(a, now, a.inputs());
        if (!cur.isNone() && cur.next.locked(c)) {
            return null; // "uninterrupted": inside an attack's lock window
        }
        SlashArt.Type type = SlashArt.charge(heldTicks, soulSpeed);
        if (type != SlashArt.Type.JACKPOT && cur.quickCharge && c.elapsed <= 3 + soulSpeed) {
            type = SlashArt.Type.JACKPOT;
        }
        return startArt(now, a, art.state(type, a.onGround()));
    }

    /** {@code SuperSlashArts}: the art's super state, when stronger than the current one. */
    public String superArt(long now, Actor a, SlashArt art) {
        resolve(now, a);
        return art == null ? null : startArt(now, a, art.state(SlashArt.Type.SUPER, a.onGround()));
    }

    private String startArt(long now, Actor a, String id) {
        if (Combos.NONE.equals(id) || id.equals(state.id) || !combos.has(id)) {
            return null;
        }
        if (state.priority > combos.get(id).priority) {
            enter(id, now, a, Cause.CHARGE);
            return id;
        }
        return null;
    }

    /** Landing: an aerial state is replaced by its timeout state ({@code FallHandler}). */
    public void land(long now, Actor a) {
        if (state.aerial) {
            enter(state.nextOfTimeout, now, a, Cause.LAND);
        }
    }

    /** Back to the root (the blade left the hand, a dodge): without its click actions' effects mattering. */
    public void reset(long now, Actor a) {
        enter(Combos.STANDBY, now, a, Cause.RESET);
    }
}
