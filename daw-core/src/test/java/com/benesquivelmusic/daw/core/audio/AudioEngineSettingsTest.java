package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioEngineSettingsTest {

    @Test
    void defaultsUseAvailableCoresMinusTwoAndDefaultBlockSize() {
        AudioEngineSettings defaults = AudioEngineSettings.defaults();
        int cores = Runtime.getRuntime().availableProcessors();
        int expected = Math.max(1, cores - 2);
        assertThat(defaults.workerPoolSize()).isEqualTo(expected);
        assertThat(defaults.minParallelBlockSize())
                .isEqualTo(AudioGraphScheduler.DEFAULT_MIN_PARALLEL_BLOCK_SIZE);
    }

    @Test
    void withersProduceCopiesWithReplacedValues() {
        AudioEngineSettings base = new AudioEngineSettings(4, 128);
        assertThat(base.withWorkerPoolSize(8))
                .isEqualTo(new AudioEngineSettings(8, 128));
        assertThat(base.withMinParallelBlockSize(256))
                .isEqualTo(new AudioEngineSettings(4, 256));
    }

    @Test
    void rejectsNonPositiveValues() {
        assertThatThrownBy(() -> new AudioEngineSettings(0, 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioEngineSettings(-1, 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioEngineSettings(4, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioEngineSettings(4, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
