package com.akashic.overkill.core;

/** The math of swings and aims, in Minecraft's conventions (yaw 0 looks south, +Z; pitch positive looks down). */
public final class Geometry {

    private Geometry() {}

    /** An angle difference in -180..180. */
    public static double wrap(double degrees) {
        double d = degrees % 360;
        if (d >= 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    /** The unit look vector of a yaw and pitch. */
    public static double[] look(double yaw, double pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new double[] { -Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p) };
    }

    /** The yaw that looks from one point toward another, on the horizontal plane. */
    public static double yawTo(double fromX, double fromZ, double toX, double toZ) {
        return Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
    }

    /** The pitch that looks from one point toward another. */
    public static double pitchTo(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        return -Math.toDegrees(Math.atan2(toY - fromY, Math.sqrt(dx * dx + dz * dz)));
    }

    /**
     * Whether a swing from the eyes, turned by {@code yaw}, {@code reach} blocks long and {@code arc} degrees wide,
     * touches a target box (centre and half sizes). Distance counts to the nearest point of the box; the angle to the
     * box's centre gets the box's own angular half-width as allowance, so a big target at the edge is still hit.
     */
    public static boolean swingHits(double ex, double ey, double ez, double yaw, double reach, double arc, double cx,
        double cy, double cz, double halfWidth, double halfHeight) {
        double dx = Math.max(Math.abs(cx - ex) - halfWidth, 0);
        double dy = Math.max(Math.abs(cy - ey) - halfHeight, 0);
        double dz = Math.max(Math.abs(cz - ez) - halfWidth, 0);
        if (dx * dx + dy * dy + dz * dz > reach * reach) {
            return false;
        }
        if (arc >= 360) {
            return true;
        }
        double flat = Math.hypot(cx - ex, cz - ez);
        if (flat <= halfWidth) {
            return true; // inside the target: always hit
        }
        double allowance = Math.toDegrees(Math.atan2(halfWidth, flat));
        return Math.abs(wrap(yawTo(ex, ez, cx, cz) - yaw)) <= arc / 2 + allowance;
    }

    /**
     * Distance along a ray (unit direction) to the point nearest a target centre, and how far off the ray that point
     * is: {along, off}. For picking what the player looks at.
     */
    public static double[] alongRay(double ox, double oy, double oz, double[] dir, double cx, double cy, double cz) {
        double vx = cx - ox;
        double vy = cy - oy;
        double vz = cz - oz;
        double along = vx * dir[0] + vy * dir[1] + vz * dir[2];
        double px = vx - dir[0] * along;
        double py = vy - dir[1] * along;
        double pz = vz - dir[2] * along;
        return new double[] { along, Math.sqrt(px * px + py * py + pz * pz) };
    }

    /** The angle in degrees between the look and the direction to a point (0: dead ahead). */
    public static double angleTo(double[] look, double ox, double oy, double oz, double cx, double cy, double cz) {
        double vx = cx - ox;
        double vy = cy - oy;
        double vz = cz - oz;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 1e-9) {
            return 0;
        }
        double dot = (vx * look[0] + vy * look[1] + vz * look[2]) / len;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }
}
