package com.benesquivelmusic.daw.acoustics.common;

import java.util.Objects;

/**
 * Mutable 3-component vector. Ported from RoomAcoustiCpp {@code Vec3}.
 */
public final class Vec3 {

    public double x;
    public double y;
    public double z;

    public Vec3() { this(0.0, 0.0, 0.0); }

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3(Vec3 other) { this(other.x, other.y, other.z); }

    public double length() { return Math.sqrt(x * x + y * y + z * z); }

    public void normalise() {
        if (x == 0.0 && y == 0.0 && z == 0.0) return;
        double len = length();
        x /= len; y /= len; z /= len;
    }

    public void roundVec() {
        x = Definitions.round(x);
        y = Definitions.round(y);
        z = Definitions.round(z);
    }

    public Vec3 addLocal(Vec3 v) { x += v.x; y += v.y; z += v.z; return this; }
    public Vec3 subLocal(Vec3 v) { x -= v.x; y -= v.y; z -= v.z; return this; }
    public Vec3 mulLocal(double a) { x *= a; y *= a; z *= a; return this; }
    public Vec3 divLocal(double a) { return mulLocal(1.0 / a); }

    // Static operations returning new vectors
    public static Vec3 add(Vec3 u, Vec3 v) { return new Vec3(u.x + v.x, u.y + v.y, u.z + v.z); }
    public static Vec3 sub(Vec3 u, Vec3 v) { return new Vec3(u.x - v.x, u.y - v.y, u.z - v.z); }
    public static Vec3 negate(Vec3 v) { return new Vec3(-v.x, -v.y, -v.z); }
    public static Vec3 mul(double a, Vec3 v) { return new Vec3(a * v.x, a * v.y, a * v.z); }
    public static Vec3 mul(Vec3 v, double a) { return mul(a, v); }
    public static Vec3 div(Vec3 v, double a) { return new Vec3(v.x / a, v.y / a, v.z / a); }

    public static double dot(Vec3 u, Vec3 v) { return u.x * v.x + u.y * v.y + u.z * v.z; }

    public static Vec3 cross(Vec3 u, Vec3 v) {
        return new Vec3(
                u.y * v.z - u.z * v.y,
                u.z * v.x - u.x * v.z,
                u.x * v.y - u.y * v.x);
    }

    public static Vec3 unitVector(Vec3 v) {
        if (v.x == 0.0 && v.y == 0.0 && v.z == 0.0) return new Vec3(v);
        return div(v, v.length());
    }

    public static Vec3 unitVectorRound(Vec3 v) {
        Vec3 copy = new Vec3(v);
        copy.roundVec();
        return unitVector(copy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vec3 v)) return false;
        return Double.compare(x, v.x) == 0 && Double.compare(y, v.y) == 0 && Double.compare(z, v.z) == 0;
    }

    @Override
    public int hashCode() { return Objects.hash(x, y, z); }

    @Override
    public String toString() { return "[ " + x + " , " + y + " , " + z + " ]"; }
}
