package com.akashic.overkill.combo;

/**
 * The world as the combo engine sees one fighter: what it asks about the player and what it asks the world to do. The
 * Bukkit layer implements it for a player; tests record the calls. Every method runs on the main thread.
 */
public interface Actor {

    /** Held keys plus {@link Input#ON_GROUND} or {@link Input#ON_AIR} (no clicks). */
    int inputs();

    boolean onGround();

    boolean inWater();

    /** SlashBlade's {@code isPowered}: Strength or Hunger. */
    boolean isPowered();

    /** Ticks the use button has been held (0 when it is not). */
    int useTicks();

    /** 0..1, the fighter's random source. */
    float random();

    /**
     * SlashBlade's {@code doSlash}: a slash effect at the eyes x 0.75 + look x 0.3 that hits on its ticks 2, 4, 6, 8
     * and 10 around the player.
     */
    void slash(float roll, Offset offset, boolean mute, boolean critical, float ratio, Knockback knockback);

    /** An area attack around the player right now ({@code reach} <= 0: the blade's reach); true when it found a target. */
    boolean areaAttack(Knockback knockback, float ratio, boolean forceHit, boolean resetHit, boolean mute, double reach);

    /** The sheathing click (SlashBlade's quick sheath sound). */
    void sheathSound();

    void sound(Sound sound, float volume, float pitch);

    double velocityY();

    void setVelocityY(double vy);

    void setVelocity(double x, double y, double z);

    /** {@code moveRelative(speed, forward)}: adds speed x the horizontal look to the velocity. */
    void addForward(double speed);

    /** Zeroes the horizontal velocity (SlashBlade's {@code multiply(0, 1, 0)}). */
    void stopHorizontal();

    /** {@code FallHandler.fallDecrease}: the air hover that weakens with every airborne tick. */
    void fallDecrease();

    /** {@code FallHandler.fallResist}: a strong hover. */
    void fallResist();

    void setFallDistance(float distance);

    /** The cosmetic body spin of {@code UserPoseOverrider.setRot} (degrees per tick; 0 resets). */
    void spin(float degrees);

    /** A slash art's effect (Judgement Cut, drive, wave edge, circle slash, void slash). */
    void art(Art art, float... args);

    /** Sounds the engine asks for. */
    enum Sound {
        TRIDENT_THROW,
        ARMOR_EQUIP_IRON,
        ATTACK_STRONG,
        PIERCING
    }

    /** Slash-art effects. */
    enum Art {
        /** Judgement Cut at the lock-on target, else where the player looks. */
        JUDGEMENT_CUT,
        /** The critical (just) Judgement Cut. */
        JUDGEMENT_CUT_JUST,
        /** A Judgement Cut on every target around (super). */
        JUDGEMENT_CUT_SUPER,
        /** args: roll, lifetime, damage, speed. */
        DRIVE,
        /** args: roll, lifetime, damage, minSpeed, maxSpeed, count. */
        WAVE_EDGE,
        /** args: yaw offset. */
        CIRCLE_SLASH,
        VOID_SLASH
    }
}
