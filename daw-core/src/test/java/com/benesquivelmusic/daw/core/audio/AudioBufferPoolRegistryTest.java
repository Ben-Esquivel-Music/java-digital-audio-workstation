package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioBufferPoolRegistryTest {

    @Test
    void shouldRegisterAndRetrievePoolsByShape() {
        AudioBufferPoolRegistry reg = new AudioBufferPoolRegistry();
        AudioBufferPool stereo = reg.register(2, 64, MixPrecision.FLOAT_32, 4);
        AudioBufferPool mono = reg.register(1, 64, MixPrecision.FLOAT_32, 4);

        assertThat(reg.size()).isEqualTo(2);
        assertThat(reg.pool(2, 64, MixPrecision.FLOAT_32)).isSameAs(stereo);
        assertThat(reg.pool(1, 64, MixPrecision.FLOAT_32)).isSameAs(mono);
        assertThat(reg.pool(2, 64, MixPrecision.DOUBLE_64)).isNull();
        assertThat(reg.pool(7, 64, MixPrecision.FLOAT_32)).isNull();
    }

    @Test
    void shouldAcquireAndReleaseByShape() {
        AudioBufferPoolRegistry reg = new AudioBufferPoolRegistry();
        reg.register(2, 64, MixPrecision.FLOAT_32, 2);

        AudioBuffer buf = reg.acquire(2, 64, MixPrecision.FLOAT_32);
        assertThat(buf).isNotNull();
        assertThat(buf.getChannels()).isEqualTo(2);
        assertThat(buf.getFrames()).isEqualTo(64);

        assertThat(reg.release(2, 64, MixPrecision.FLOAT_32, buf)).isTrue();
    }

    @Test
    void shouldReturnNullForUnknownShape() {
        AudioBufferPoolRegistry reg = new AudioBufferPoolRegistry();
        reg.register(2, 64, MixPrecision.FLOAT_32, 1);

        assertThat(reg.acquire(2, 64, MixPrecision.DOUBLE_64)).isNull();
        assertThat(reg.release(2, 64, MixPrecision.DOUBLE_64, new AudioBuffer(2, 64))).isFalse();
    }

    @Test
    void precisionIsPartOfTheKey() {
        AudioBufferPoolRegistry reg = new AudioBufferPoolRegistry();
        AudioBufferPool f = reg.register(2, 64, MixPrecision.FLOAT_32, 1);
        AudioBufferPool d = reg.register(2, 64, MixPrecision.DOUBLE_64, 1);

        assertThat(f).isNotSameAs(d);
        assertThat(reg.pool(2, 64, MixPrecision.FLOAT_32)).isSameAs(f);
        assertThat(reg.pool(2, 64, MixPrecision.DOUBLE_64)).isSameAs(d);
    }

    @Test
    void shouldRejectDuplicateShapeRegistration() {
        AudioBufferPoolRegistry reg = new AudioBufferPoolRegistry();
        reg.register(2, 64, MixPrecision.FLOAT_32, 1);
        assertThatThrownBy(() -> reg.register(2, 64, MixPrecision.FLOAT_32, 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
