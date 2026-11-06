package com.akashic.overkill.core;

/** When a move can start: on the ground or in the air, and with which movement held. */
public enum Condition {
    /** Anywhere. */
    ANY,
    /** On the ground, neither sneaking nor sprinting. */
    GROUND,
    /** On the ground, sprinting (a lunge). */
    SPRINT,
    /** On the ground, sneaking (a launcher). */
    SNEAK,
    /** In the air, not sneaking. */
    AIR,
    /** In the air, sneaking (a plunge). */
    AIR_SNEAK;

    /** Whether the condition holds for the attacker now. */
    public boolean matches(Stance s) {
        switch (this) {
            case ANY:
                return true;
            case GROUND:
                return s.onGround && !s.sneaking && !s.sprinting;
            case SPRINT:
                return s.onGround && s.sprinting && !s.sneaking;
            case SNEAK:
                return s.onGround && s.sneaking;
            case AIR:
                return !s.onGround && !s.sneaking;
            case AIR_SNEAK:
                return !s.onGround && s.sneaking;
            default:
                return false;
        }
    }

    /** Whether the client, which only knows the plain ground or air chain, may predict a move with this condition. */
    public boolean predictable(boolean air) {
        return this == ANY || (air ? this == AIR : this == GROUND);
    }
}
