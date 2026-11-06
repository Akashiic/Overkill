package com.akashic.overkill.core;

/** How the attacker stands at the instant of an attack. Immutable. */
public final class Stance {

    public static final Stance GROUND = new Stance(true, false, false);
    public static final Stance AIR = new Stance(false, false, false);

    public final boolean onGround;
    public final boolean sneaking;
    public final boolean sprinting;

    public Stance(boolean onGround, boolean sneaking, boolean sprinting) {
        this.onGround = onGround;
        this.sneaking = sneaking;
        this.sprinting = sprinting;
    }
}
