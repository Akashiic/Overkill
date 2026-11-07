package com.akashic.overkill.bukkit.combat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.akashic.overkill.combo.Knockback;

/**
 * A slash effect still hitting (SlashBlade's {@code EntitySlashEffect}): it lives 10 ticks and, on its ticks 2, 4, 6,
 * 8 and 10, runs an area attack around its owner (the player as he is at that tick), hitting each target once.
 */
final class SlashEffect {

    static final int LIFETIME = 10;

    final float ratio;
    final Knockback knockback;
    final boolean mute;
    final boolean critical;
    final Set<UUID> hit = new HashSet<>();
    int age;

    SlashEffect(float ratio, Knockback knockback, boolean mute, boolean critical) {
        this.ratio = ratio;
        this.knockback = knockback;
        this.mute = mute;
        this.critical = critical;
    }

    /** The ratio its hits use (the critical flag adds 10%). */
    float hitRatio() {
        return ratio * (critical ? 1.1f : 1.0f);
    }

    boolean hitsThisTick() {
        return age % 2 == 0 && age >= 2 && age <= LIFETIME;
    }

    boolean done() {
        return age > LIFETIME;
    }
}
