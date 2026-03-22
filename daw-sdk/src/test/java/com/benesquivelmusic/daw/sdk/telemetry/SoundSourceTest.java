package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoundSourceTest {

    @Test
    void shouldCreateWithValidParameters() {
        var pos = new Position3D(5.0, 4.0, 1.0);
        var source = new SoundSource("Guitar Amp", pos, 85.0);

        assertThat(source.name()).isEqualTo("Guitar Amp");
        assertThat(source.position()).isEqualTo(pos);
        assertThat(source.powerDb()).isEqualTo(85.0);
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new SoundSource(null, new Position3D(0, 0, 0), 80))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPosition() {
        assertThatThrownBy(() -> new SoundSource("Source", null, 80))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldAllowNegativePowerDb() {
        var source = new SoundSource("Quiet", new Position3D(0, 0, 0), -10.0);
        assertThat(source.powerDb()).isEqualTo(-10.0);
    }
}
