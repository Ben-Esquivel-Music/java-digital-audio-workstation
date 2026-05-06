package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.Mixer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link AudioEngine} wires the multi-core graph scheduler
 * (story 125) into the live render path.
 */
class AudioEngineMultiCoreTest {

    @Test
    void defaultsCreatePoolAndSchedulerOnStartAndAttachToMixer() {
        AudioEngineSettings settings = new AudioEngineSettings(
                4, AudioGraphScheduler.DEFAULT_MIN_PARALLEL_BLOCK_SIZE);
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY, settings);
        Mixer mixer = new Mixer();
        engine.setMixer(mixer);

        // Before start: no scheduler/pool wired yet.
        assertThat(engine.getWorkerPool()).isNull();
        assertThat(engine.getGraphScheduler()).isNull();
        assertThat(engine.getActiveThreadCount()).isZero();
        assertThat(engine.getWorkerPoolSize()).isEqualTo(4);

        engine.start();
        try {
            assertThat(engine.getWorkerPool()).isNotNull();
            assertThat(engine.getWorkerPool().size()).isEqualTo(4);
            assertThat(engine.getGraphScheduler()).isNotNull();
            // Mixer.mixDown will dispatch through this scheduler.
            assertThat(mixer.getGraphScheduler()).isSameAs(engine.getGraphScheduler());
            // No block has been processed yet.
            assertThat(engine.getActiveThreadCount()).isZero();
        } finally {
            engine.stop();
        }

        // After stop: pool closed and detached from the mixer.
        assertThat(engine.getWorkerPool()).isNull();
        assertThat(engine.getGraphScheduler()).isNull();
        assertThat(mixer.getGraphScheduler()).isNull();
    }

    @Test
    void poolSizeOneSkipsSchedulerCreation() {
        AudioEngine engine = new AudioEngine(
                AudioFormat.CD_QUALITY, new AudioEngineSettings(1, 64));
        Mixer mixer = new Mixer();
        engine.setMixer(mixer);

        engine.start();
        try {
            // pool size 1 => single-threaded; no pool/scheduler created.
            assertThat(engine.getWorkerPool()).isNull();
            assertThat(engine.getGraphScheduler()).isNull();
            assertThat(mixer.getGraphScheduler()).isNull();
            assertThat(engine.getActiveThreadCount()).isZero();
        } finally {
            engine.stop();
        }
    }

    @Test
    void setMixerReinstallsSchedulerOnRunningEngine() {
        AudioEngine engine = new AudioEngine(
                AudioFormat.CD_QUALITY, new AudioEngineSettings(2, 64));
        engine.start();
        try {
            // Hot-swap mixer while running should propagate the scheduler.
            Mixer m1 = new Mixer();
            engine.setMixer(m1);
            assertThat(m1.getGraphScheduler()).isSameAs(engine.getGraphScheduler());

            Mixer m2 = new Mixer();
            engine.setMixer(m2);
            assertThat(m2.getGraphScheduler()).isSameAs(engine.getGraphScheduler());
            // The previous mixer must have its scheduler detached so stale
            // references do not attempt parallel dispatch after pool close.
            assertThat(m1.getGraphScheduler()).isNull();
        } finally {
            engine.stop();
        }
    }

    @Test
    void engineSettingsCannotChangeWhileRunning() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.start();
        try {
            assertThatThrownBy(() ->
                    engine.setEngineSettings(new AudioEngineSettings(2, 64)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            engine.stop();
        }
    }

    @Test
    void defaultConstructorUsesDefaultEngineSettings() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        assertThat(engine.getEngineSettings()).isEqualTo(AudioEngineSettings.defaults());
    }
}
