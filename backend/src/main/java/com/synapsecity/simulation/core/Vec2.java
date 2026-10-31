package com.synapsecity.simulation.core;

/**
 * Immutable 2D vector in the simulation's ground plane (metres).
 * The simulation uses a right-handed x/y plane with counter-clockwise angles;
 * the renderer maps y onto -z so that chirality (and right-hand traffic) is preserved.
 */
public record Vec2(double x, double y) {

    public static final Vec2 ZERO = new Vec2(0, 0);

    public static Vec2 fromAngle(double radians) {
        return new Vec2(Math.cos(radians), Math.sin(radians));
    }

    public Vec2 add(Vec2 o) {
        return new Vec2(x + o.x, y + o.y);
    }

    public Vec2 sub(Vec2 o) {
        return new Vec2(x - o.x, y - o.y);
    }

    public Vec2 scale(double k) {
        return new Vec2(x * k, y * k);
    }

    public double dot(Vec2 o) {
        return x * o.x + y * o.y;
    }

    public double cross(Vec2 o) {
        return x * o.y - y * o.x;
    }

    public double length() {
        return Math.hypot(x, y);
    }

    public double distance(Vec2 o) {
        return Math.hypot(x - o.x, y - o.y);
    }

    public Vec2 normalized() {
        double l = length();
        return l < 1e-12 ? ZERO : new Vec2(x / l, y / l);
    }

    /** Unit normal pointing to the right of this direction (the kerb side under right-hand traffic). */
    public Vec2 rightNormal() {
        return new Vec2(y, -x);
    }

    public double angle() {
        return Math.atan2(y, x);
    }

    public Vec2 lerp(Vec2 o, double t) {
        return new Vec2(x + (o.x - x) * t, y + (o.y - y) * t);
    }

    /** Signed angle (radians, CCW positive) rotating this direction onto {@code o}. */
    public double signedAngleTo(Vec2 o) {
        return Math.atan2(cross(o), dot(o));
    }

    /** Distance from point {@code p} to the segment a-b. */
    public static double pointSegmentDistance(Vec2 p, Vec2 a, Vec2 b) {
        Vec2 ab = b.sub(a);
        double len2 = ab.dot(ab);
        if (len2 < 1e-12) {
            return p.distance(a);
        }
        double t = Math.max(0, Math.min(1, p.sub(a).dot(ab) / len2));
        return p.distance(a.add(ab.scale(t)));
    }
}
