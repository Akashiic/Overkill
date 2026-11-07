package com.akashic.overkill.combo;

/**
 * Where a click goes from a state (SlashBlade's {@code next} functions). Returning the state's own id means the click
 * is ignored ({@code progressCombo} drops it, no buffer).
 */
@FunctionalInterface
public interface Next {

    String apply(ComboState.Ctx c);

    /**
     * Whether the state is still inside a lock window: SlashBlade's {@code doChargeAction} refuses a slash art then (its
     * reference check matches only {@code TimeoutNext}).
     */
    default boolean locked(ComboState.Ctx c) {
        return false;
    }

    static Next to(String id) {
        return c -> id;
    }

    /** The click is always ignored (an uninterruptible state). */
    static Next self() {
        return c -> c.state.id;
    }

    /** SlashBlade's {@code TimeoutNext.buildFromFrame}: clicks before {@code (int)(frames*2/3)} ticks are dropped. */
    static Next lock(int frames, String target) {
        return lock(frames, to(target));
    }

    static Next lock(int frames, Next target) {
        int ticks = ComboState.ticks(frames);
        return new Next() {

            @Override
            public String apply(ComboState.Ctx c) {
                return ticks <= c.elapsed ? target.apply(c) : c.state.id;
            }

            @Override
            public boolean locked(ComboState.Ctx c) {
                return c.elapsed < ticks;
            }
        };
    }

    /** {@code isPowered ? powered : normal} (Strength or Hunger). */
    static Next powered(String powered, String normal) {
        return c -> c.actor.isPowered() ? powered : normal;
    }

    /** The root command table ({@code ComboCommands.initStandByCommand}). */
    static Next root() {
        return c -> c.machine.combos()
            .rootCommand(c.inputs);
    }
}
