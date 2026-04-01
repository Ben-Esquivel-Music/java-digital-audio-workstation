package com.benesquivelmusic.daw.acoustics.common;

import java.util.Objects;

/**
 * Mutable quaternion (w, x, y, z). Ported from RoomAcoustiCpp {@code Vec4}.
 */
public final class Vec4 {

    public double w;
    public double x;
    public double y;
    public double z;

    public Vec4() { this(0.0, 0.0, 0.0, 0.0); }

    public Vec4(double w, double x, double y, double z) {
        this.w = w; this.x = x; this.y = y; this.z = z;
    }

    public Vec4(double w, Vec3 v) { this(w, v.x, v.y, v.z); }

    public Vec4(Vec3 v) { this(0.0, v.x, v.y, v.z); }

    public Vec4(Vec4 other) { this(other.w, other.x, other.y, other.z); }

    /** Returns the forward vector of this quaternion rotation. */
    public Vec3 forward() {
        Vec3 fwd = new Vec3();
        fwd.x = 2.0 * (x * z + w * y);
        fwd.y = 2.0 * (y * z - w * x);
        fwd.z = 1.0 - 2.0 * (x * x + y * y);
        fwd.normalise();
        return fwd;
    }

    public double squareNormal() { return w * w + x * x + y * y + z * z; }

    public Vec4 conjugate() { return new Vec4(w, -x, -y, -z); }

    public Vec4 inverse() {
        double ns = squareNormal();
        if (ns == 0.0) return new Vec4();
        Vec4 conj = conjugate();
        return new Vec4(conj.w / ns, conj.x / ns, conj.y / ns, conj.z / ns);
    }

    public Vec3 rotateVector(Vec3 v) {
        Vec4 rotated = multiply(this, multiply(new Vec4(v), inverse()));
        return new Vec3(rotated.x, rotated.y, rotated.z);
    }

    public static Vec4 multiply(Vec4 a, Vec4 b) {
        return new Vec4(
                a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
                a.w * b.x + a.x * b.w - a.y * b.z + a.z * b.y,
                a.w * b.y + a.x * b.z + a.y * b.w - a.z * b.x,
                a.w * b.z - a.x * b.y + a.y * b.x + a.z * b.w);
    }

    public static Vec4 div(Vec4 v, double a) {
        return new Vec4(v.w / a, v.x / a, v.y / a, v.z / a);
    }

    public static Vec4 negate(Vec4 v) { return new Vec4(-v.w, -v.x, -v.y, -v.z); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vec4 v)) return false;
        return Double.compare(w, v.w) == 0 && Double.compare(x, v.x) == 0
                && Double.compare(y, v.y) == 0 && Double.compare(z, v.z) == 0;
    }

    @Override
    public int hashCode() { return Objects.hash(w, x, y, z); }

    @Override
    public String toString() { return "[ " + w + " , " + x + " , " + y + " , " + z + " ]"; }
}
