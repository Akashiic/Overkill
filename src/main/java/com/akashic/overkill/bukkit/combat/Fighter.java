package com.akashic.overkill.bukkit.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import com.akashic.overkill.combo.Actor;
import com.akashic.overkill.combo.ComboMachine;
import com.akashic.overkill.combo.Concentration;
import com.akashic.overkill.combo.Input;
import com.akashic.overkill.combo.Knockback;
import com.akashic.overkill.combo.Offset;
import com.akashic.overkill.core.Blade;
import com.akashic.overkill.core.BladeStats;

/**
 * One player with a blade: the combo machine, the concentration rank, the use button, the motion the server can
 * estimate (a 1.7.10 server does not know a player's velocity: it is the position change of the last tick), the
 * hover budget, the lock target, the hit counter, and the slash effects still hitting. Its {@link Actor} side turns the
 * combo engine's requests into the world through {@link Combat}. Main thread only.
 */
final class Fighter implements Actor {

    final Player player;
    final Combat combat;
    final ComboMachine machine;
    final Concentration rank = new Concentration();
    final Random random = new Random();
    final List<SlashEffect> slashes = new ArrayList<>();

    Blade blade;
    BladeStats stats;
    int useTicks;
    boolean using;
    long lastClick = -1;
    boolean keysBound;

    double prevX;
    double prevY;
    double prevZ;
    double dx;
    double dy;
    double dz;
    boolean wasOnGround = true;
    float fallDecreaseRate;

    /** The hotbar slot the blade is in, its stats changed since written to the item, when last written. */
    int slot = -1;
    boolean dirty;
    boolean levelUp;
    long lastFlush;

    LivingEntity lockTarget;
    int hits;
    long lastHit = -1000;

    Fighter(Player player, Combat combat) {
        this.player = player;
        this.combat = combat;
        this.machine = new ComboMachine(combat.combos(), (s, cause, now) -> combat.entered(this, s, cause, now));
        Location l = player.getLocation();
        prevX = l.getX();
        prevY = l.getY();
        prevZ = l.getZ();
    }

    /** The estimated motion, refreshed once per tick before anything uses it. */
    void sampleMotion() {
        Location l = player.getLocation();
        dx = l.getX() - prevX;
        dy = l.getY() - prevY;
        dz = l.getZ() - prevZ;
        prevX = l.getX();
        prevY = l.getY();
        prevZ = l.getZ();
    }

    // ---- Actor ----------------------------------------------------------------------------------------------------

    @Override
    public int inputs() {
        int bits = combat.heldInputs(player);
        if (player.isSneaking()) {
            bits |= Input.SNEAK;
        }
        if (player.isBlocking()) {
            bits |= Input.R_DOWN;
        }
        return bits | (onGround() ? Input.ON_GROUND : Input.ON_AIR);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onGround() {
        return player.isOnGround();
    }

    @Override
    public boolean inWater() {
        org.bukkit.Material m = player.getLocation()
            .getBlock()
            .getType();
        return m == org.bukkit.Material.WATER || m == org.bukkit.Material.STATIONARY_WATER;
    }

    @Override
    public boolean isPowered() {
        return player.hasPotionEffect(PotionEffectType.INCREASE_DAMAGE) || player.hasPotionEffect(PotionEffectType.HUNGER);
    }

    @Override
    public int useTicks() {
        return using ? useTicks : 0;
    }

    @Override
    public float random() {
        return random.nextFloat();
    }

    @Override
    public void slash(float roll, Offset offset, boolean mute, boolean critical, float ratio, Knockback knockback) {
        combat.slash(this, roll, 0, offset, mute, critical, ratio, knockback);
    }

    @Override
    public boolean areaAttack(Knockback knockback, float ratio, boolean forceHit, boolean resetHit, boolean mute,
        double reach) {
        return !combat.areaAttack(this, knockback, ratio, forceHit, resetHit, mute, reach, null)
            .isEmpty();
    }

    @Override
    public void sheathSound() {
        combat.sound(player.getLocation(), Sfx.SHEATH, 1.0f, 1.0f);
    }

    @Override
    public void sound(Sound sound, float volume, float pitch) {
        combat.sound(player.getLocation(), Sfx.of(sound), volume, pitch);
    }

    @Override
    public double velocityY() {
        return dy;
    }

    @Override
    public void setVelocityY(double vy) {
        dy = vy;
        player.setVelocity(new Vector(dx, vy, dz));
    }

    @Override
    public void setVelocity(double x, double y, double z) {
        dx = x;
        dy = y;
        dz = z;
        player.setVelocity(new Vector(x, y, z));
    }

    @Override
    public void addForward(double speed) {
        double yaw = Math.toRadians(player.getLocation()
            .getYaw());
        dx += -Math.sin(yaw) * speed;
        dz += Math.cos(yaw) * speed;
        player.setVelocity(new Vector(dx, dy, dz));
    }

    @Override
    public void stopHorizontal() {
        dx = 0;
        dz = 0;
        player.setVelocity(new Vector(0, dy, 0));
    }

    /** SlashBlade's {@code FallHandler.fallDecrease}: the hover weakens with each airborne tick until landing. */
    @Override
    public void fallDecrease() {
        if (onGround()) {
            return;
        }
        player.setFallDistance(1);
        float ratio = fallDecreaseRate;
        fallDecreaseRate = Math.min(1.0f, fallDecreaseRate + 0.05f);
        double g = 0.08 * 0.85;
        if (dy < 0) {
            setVelocityY((dy + g) * ratio);
        }
    }

    @Override
    public void fallResist() {
        if (onGround()) {
            return;
        }
        player.setFallDistance(1);
        if (dy < 0) {
            setVelocityY(dy + 0.08 * 0.85 + 0.002);
        }
    }

    @Override
    public void setFallDistance(float distance) {
        player.setFallDistance(distance);
    }

    @Override
    public void spin(float degrees) {
        // cosmetic body spin: not drawn yet
    }

    @Override
    public void art(Art art, float... args) {
        combat.art(this, art, args);
    }
}
