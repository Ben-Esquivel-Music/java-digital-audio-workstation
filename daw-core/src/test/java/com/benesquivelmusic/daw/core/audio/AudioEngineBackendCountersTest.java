package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backend ledger through the real wiring (story 337, ruling E4): the
 * engine attaches the open backend's {@code threadCounters()} to its health
 * record when a stream opens and folds them when it closes, so a backend's
 * counts reach the record live during a stream, survive the close, and are
 * never counted twice when the same backend instance is reopened.
 */
class AudioEngineBackendCountersTest {

    private AudioEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stopAudioOutput();
            engine.stop();
        }
    }

    @Test
    void theOpenBackendsCountersReachTheRecordLiveAndSurviveTheClose() {
        MockAudioBackend backend = new MockAudioBackend();
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        IllegalStateException fault = new IllegalStateException("callback fault");

        backend.setThreadCounters(new BackendThreadCounters(3, 2, 0, 1, OptionalLong.of(120),
                Optional.of(fault), Optional.empty(), Optional.empty()));

        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK)).isEqualTo(3);
        assertThat(engine.audioHealth().total(AudioFailureCategory.INPUT)).isEqualTo(2);
        assertThat(engine.audioHealth().total(AudioFailureCategory.DEVICE)).isEqualTo(1);
        assertThat(engine.audioHealth().deviceCallbackPeriods()).hasValue(120);
        assertThat(engine.audioHealth().lastFailure(AudioFailureCategory.CALLBACK)).containsSame(fault);

        engine.stopAudioOutput();

        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK))
                .as("the close folds the backend's counts into the record")
                .isEqualTo(3);
        backend.setThreadCounters(new BackendThreadCounters(40, 2, 0, 1, OptionalLong.of(900),
                Optional.of(fault), Optional.empty(), Optional.empty()));
        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK))
                .as("a closed stream's backend is no longer read")
                .isEqualTo(3);
    }

    @Test
    void reopeningTheSameBackendNeverCountsItsHistoryTwice() {
        MockAudioBackend backend = new MockAudioBackend();
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        backend.setThreadCounters(counters(4));
        engine.stopAudioOutput();

        engine.startAudioOutput();
        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK))
                .as("the reopened backend's history is the new baseline")
                .isEqualTo(4);

        backend.setThreadCounters(counters(7));
        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK)).isEqualTo(7);
    }

    @Test
    void countsABackendReportedBeforeTheStreamOpenedAreItsHistoryNotNewFaults() {
        MockAudioBackend backend = new MockAudioBackend();
        backend.setThreadCounters(counters(9));
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));

        engine.startAudioOutput();

        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK)).isZero();
    }

    private static BackendThreadCounters counters(long callbackFaults) {
        return new BackendThreadCounters(callbackFaults, 0, 0, 0, OptionalLong.of(callbackFaults * 10),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static StreamingProvision provision(MockAudioBackend backend) {
        DeviceId device = DeviceId.defaultFor(backend.name());
        return new StreamingProvision(
                backend.name(), List.of(new BackendStreamRung(backend, device)));
    }
}
