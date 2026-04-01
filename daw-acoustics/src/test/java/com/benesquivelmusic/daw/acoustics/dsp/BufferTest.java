package com.benesquivelmusic.daw.acoustics.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BufferTest {

    @Test
    void defaultConstructor() {
        Buffer b = new Buffer();
        assertThat(b.length()).isEqualTo(1);
    }

    @Test
    void constructWithSize() {
        Buffer b = new Buffer(128);
        assertThat(b.length()).isEqualTo(128);
        for (int i = 0; i < 128; i++)
            assertThat(b.get(i)).isEqualTo(0.0);
    }

    @Test
    void setAndGet() {
        Buffer b = new Buffer(10);
        b.set(5, 42.0);
        assertThat(b.get(5)).isEqualTo(42.0);
    }

    @Test
    void reset() {
        Buffer b = new Buffer(new double[]{1, 2, 3});
        b.reset();
        for (int i = 0; i < 3; i++)
            assertThat(b.get(i)).isEqualTo(0.0);
    }

    @Test
    void resize() {
        Buffer b = new Buffer(new double[]{1, 2, 3});
        b.resize(5);
        assertThat(b.length()).isEqualTo(5);
        assertThat(b.get(0)).isEqualTo(1.0);
        assertThat(b.get(2)).isEqualTo(3.0);
        assertThat(b.get(4)).isEqualTo(0.0);
    }

    @Test
    void mulLocal() {
        Buffer b = new Buffer(new double[]{1, 2, 3});
        b.mulLocal(2.0);
        assertThat(b.get(0)).isEqualTo(2.0);
        assertThat(b.get(2)).isEqualTo(6.0);
    }

    @Test
    void addLocalBuffer() {
        Buffer a = new Buffer(new double[]{1, 2, 3});
        Buffer b = new Buffer(new double[]{4, 5, 6});
        a.addLocal(b);
        assertThat(a.get(0)).isEqualTo(5.0);
        assertThat(a.get(2)).isEqualTo(9.0);
    }

    @Test
    void valid() {
        Buffer b = new Buffer(new double[]{1, 2, 3});
        assertThat(b.valid()).isTrue();
        b.set(0, Double.NaN);
        assertThat(b.valid()).isFalse();
    }

    @Test
    void equality() {
        Buffer a = new Buffer(new double[]{1, 2, 3});
        Buffer b = new Buffer(new double[]{1, 2, 3});
        assertThat(a).isEqualTo(b);
    }
}
