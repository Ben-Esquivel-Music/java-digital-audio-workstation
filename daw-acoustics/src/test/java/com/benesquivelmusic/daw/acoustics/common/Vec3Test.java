package com.benesquivelmusic.daw.acoustics.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class Vec3Test {

    @Test
    void defaultConstructorIsZero() {
        Vec3 v = new Vec3();
        assertThat(v.x).isEqualTo(0.0);
        assertThat(v.y).isEqualTo(0.0);
        assertThat(v.z).isEqualTo(0.0);
    }

    @Test
    void lengthOfUnitVectors() {
        assertThat(new Vec3(1, 0, 0).length()).isCloseTo(1.0, within(1e-10));
        assertThat(new Vec3(0, 1, 0).length()).isCloseTo(1.0, within(1e-10));
        assertThat(new Vec3(0, 0, 1).length()).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void normalise() {
        Vec3 v = new Vec3(3, 0, 0);
        v.normalise();
        assertThat(v.x).isCloseTo(1.0, within(1e-10));
        assertThat(v.length()).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void normaliseZeroVectorIsNoop() {
        Vec3 v = new Vec3();
        v.normalise();
        assertThat(v.length()).isEqualTo(0.0);
    }

    @Test
    void dotProduct() {
        Vec3 u = new Vec3(1, 2, 3);
        Vec3 v = new Vec3(4, 5, 6);
        assertThat(Vec3.dot(u, v)).isCloseTo(32.0, within(1e-10));
    }

    @Test
    void crossProduct() {
        Vec3 u = new Vec3(1, 0, 0);
        Vec3 v = new Vec3(0, 1, 0);
        Vec3 cross = Vec3.cross(u, v);
        assertThat(cross.z).isCloseTo(1.0, within(1e-10));
        assertThat(cross.x).isCloseTo(0.0, within(1e-10));
        assertThat(cross.y).isCloseTo(0.0, within(1e-10));
    }

    @Test
    void addAndSub() {
        Vec3 u = new Vec3(1, 2, 3);
        Vec3 v = new Vec3(4, 5, 6);
        Vec3 sum = Vec3.add(u, v);
        assertThat(sum.x).isEqualTo(5.0);
        assertThat(sum.y).isEqualTo(7.0);
        assertThat(sum.z).isEqualTo(9.0);

        Vec3 diff = Vec3.sub(u, v);
        assertThat(diff.x).isEqualTo(-3.0);
    }

    @Test
    void scalarMultiply() {
        Vec3 v = new Vec3(1, 2, 3);
        Vec3 result = Vec3.mul(2.0, v);
        assertThat(result.x).isEqualTo(2.0);
        assertThat(result.y).isEqualTo(4.0);
        assertThat(result.z).isEqualTo(6.0);
    }

    @Test
    void equality() {
        Vec3 u = new Vec3(1, 2, 3);
        Vec3 v = new Vec3(1, 2, 3);
        assertThat(u).isEqualTo(v);
    }

    @Test
    void unitVector() {
        Vec3 v = new Vec3(3, 4, 0);
        Vec3 unit = Vec3.unitVector(v);
        assertThat(unit.length()).isCloseTo(1.0, within(1e-10));
        assertThat(unit.x).isCloseTo(0.6, within(1e-10));
        assertThat(unit.y).isCloseTo(0.8, within(1e-10));
    }
}
