package com.benesquivelmusic.daw.acoustics.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class Vec4Test {

    @Test
    void forwardOfIdentityQuaternion() {
        Vec4 identity = new Vec4(1, 0, 0, 0);
        Vec3 forward = identity.forward();
        assertThat(forward.x).isCloseTo(0.0, within(1e-10));
        assertThat(forward.y).isCloseTo(0.0, within(1e-10));
        assertThat(forward.z).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void conjugateAndInverse() {
        Vec4 q = new Vec4(1, 2, 3, 4);
        Vec4 conj = q.conjugate();
        assertThat(conj.w).isEqualTo(1.0);
        assertThat(conj.x).isEqualTo(-2.0);
        assertThat(conj.y).isEqualTo(-3.0);
        assertThat(conj.z).isEqualTo(-4.0);

        Vec4 inv = q.inverse();
        Vec4 product = Vec4.multiply(q, inv);
        // Should be close to identity quaternion (1, 0, 0, 0)
        assertThat(product.w).isCloseTo(1.0, within(1e-10));
        assertThat(product.x).isCloseTo(0.0, within(1e-10));
        assertThat(product.y).isCloseTo(0.0, within(1e-10));
        assertThat(product.z).isCloseTo(0.0, within(1e-10));
    }

    @Test
    void rotateVectorByIdentity() {
        Vec4 identity = new Vec4(1, 0, 0, 0);
        Vec3 v = new Vec3(1, 2, 3);
        Vec3 rotated = identity.rotateVector(v);
        assertThat(rotated.x).isCloseTo(1.0, within(1e-10));
        assertThat(rotated.y).isCloseTo(2.0, within(1e-10));
        assertThat(rotated.z).isCloseTo(3.0, within(1e-10));
    }
}
