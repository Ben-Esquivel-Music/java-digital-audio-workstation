package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioEngineTest {

    @Test
    void shouldStartAndStop() {
        var engine = new AudioEngine(AudioFormat.CD_QUALITY);

        assertThat(engine.isRunning()).isFalse();

        assertThat(engine.start()).isTrue();
        assertThat(engine.isRunning()).isTrue();

        // starting again should be a no-op
        assertThat(engine.start()).isFalse();

        assertThat(engine.stop()).isTrue();
        assertThat(engine.isRunning()).isFalse();

        // stopping again should be a no-op
        assertThat(engine.stop()).isFalse();
    }

    @Test
    void shouldReturnConfiguredFormat() {
        var engine = new AudioEngine(AudioFormat.STUDIO_QUALITY);
        assertThat(engine.getFormat()).isEqualTo(AudioFormat.STUDIO_QUALITY);
    }
}
