package com.akashic.overkill.combo;

/**
 * SlashBlade's {@code InputCommand} as bits: the keys held (sent by the Kunftig client), the transient clicks, and
 * ground or air (from the server). Combo states and the root command table test sets of these.
 */
public final class Input {

    public static final int FORWARD = 1;
    public static final int BACK = 1 << 1;
    public static final int LEFT = 1 << 2;
    public static final int RIGHT = 1 << 3;
    public static final int SNEAK = 1 << 4;
    /** The use (right) button held. */
    public static final int R_DOWN = 1 << 5;
    /** The attack (left) button held. */
    public static final int L_DOWN = 1 << 6;
    /** The summon (middle) button held. */
    public static final int M_DOWN = 1 << 7;
    /** A right click, only while it is processed. */
    public static final int R_CLICK = 1 << 8;
    /** A left click on an entity, only while it is processed. */
    public static final int L_CLICK = 1 << 9;
    public static final int ON_GROUND = 1 << 10;
    public static final int ON_AIR = 1 << 11;
    /** SlashBlade's special-move key (its "SPRINT" bit, default V). */
    public static final int SPECIAL = 1 << 12;
    public static final int JUMP = 1 << 13;

    private Input() {}

    public static boolean has(int set, int bits) {
        return (set & bits) == bits;
    }
}
