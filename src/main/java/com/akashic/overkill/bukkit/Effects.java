package com.akashic.overkill.bukkit;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.util.Vector;

import com.akashic.kunftig.api.bukkit.particle.KunftigParticles;
import com.akashic.kunftig.api.particle.KunftigParticle;
import com.akashic.overkill.core.Geometry;

/**
 * Particles and sounds every player sees and hears, with or without Kunftig on the client: the cut arcs drawn in
 * particles are what players without the 3D models see of a swing. Modern particles reach Kunftig clients with Et
 * Futurum; the rest see the closest vanilla one.
 */
final class Effects {

    private final boolean particles;

    Effects(boolean particles) {
        this.particles = particles;
    }

    void particle(Location at, KunftigParticle p, int count, double spread, double speed) {
        if (particles) {
            KunftigParticles.builder(p)
                .location(at)
                .count(count)
                .offset(spread, spread, spread)
                .extra(speed)
                .receivers(32)
                .spawn();
        }
    }

    void dust(Location at, int rgb, int count, double spread) {
        if (particles) {
            KunftigParticles.builder(KunftigParticle.DUST)
                .location(at)
                .color(rgb)
                .count(count)
                .offset(spread, spread, spread)
                .receivers(32)
                .spawn();
        }
    }

    /**
     * A cut drawn as an arc of particles in front of the eyes: {@code arc} degrees wide, {@code reach} out, tilted by
     * {@code tilt} degrees (0 horizontal, 90 vertical).
     */
    void arc(Location eye, double reach, double arc, double tilt, int rgb) {
        double yaw = eye.getYaw();
        double width = Math.min(arc, 300);
        int points = (int) Math.max(6, width / 12);
        double r = reach * 0.8;
        double t = Math.toRadians(tilt);
        for (int i = 0; i <= points; i++) {
            double a = Math.toRadians(-width / 2 + width * i / points);
            // in the swing's plane: forward and sideways, then tilted about the forward axis
            double side = Math.sin(a) * r;
            double fwd = Math.cos(a) * r;
            double up = side * Math.sin(t);
            side = side * Math.cos(t);
            double[] f = Geometry.look(yaw, 0);
            double sx = -f[2];
            double sz = f[0];
            Location p = eye.clone()
                .add(f[0] * fwd + sx * side, up - 0.2, f[2] * fwd + sz * side);
            if (i % 2 == 0) {
                particle(p, KunftigParticle.END_ROD, 1, 0, 0);
            } else {
                dust(p, rgb, 1, 0.02);
            }
        }
    }

    /** A ring on the ground around a point. */
    void ring(Location center, double radius, int rgb) {
        int points = (int) Math.max(12, radius * 10);
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            Location p = center.clone()
                .add(Math.cos(a) * radius, 0.3, Math.sin(a) * radius);
            dust(p, rgb, 1, 0.02);
            if (i % 3 == 0) {
                particle(p, KunftigParticle.CRIT, 1, 0, 0.05);
            }
        }
    }

    /** A line of particles from one point to another (a summoned sword's trail, a blink). */
    void line(Location from, Location to, KunftigParticle p, double step) {
        Vector d = to.toVector()
            .subtract(from.toVector());
        double len = d.length();
        if (len < 1e-6) {
            return;
        }
        d.multiply(1 / len);
        for (double s = 0; s <= len; s += step) {
            particle(
                from.clone()
                    .add(d.clone()
                        .multiply(s)),
                p,
                1,
                0,
                0);
        }
    }

    static void sound(Location at, Sound sound, float volume, float pitch) {
        World w = at.getWorld();
        if (w != null) {
            w.playSound(at, sound, volume, pitch);
        }
    }
}
