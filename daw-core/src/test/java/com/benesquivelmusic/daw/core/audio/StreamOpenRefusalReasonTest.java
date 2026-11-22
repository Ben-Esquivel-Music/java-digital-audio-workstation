package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.AudioEngineOutputTest.SynchronousTestBackend;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The engine names why it refused an open by a typed reason, never only by
 * its message (story 338, ruling E112): a device that is not there, a format
 * it cannot render, and a previous stream that has not let go. The refusals
 * on a wedged pump, a handle held by a control panel, a deferred release and
 * an abandoned ladder are pinned in {@link AudioEngineOutputTest}.
 */
class StreamOpenRefusalReasonTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 512);

    private final AudioEngine engine = new AudioEngine(FORMAT);

    @AfterEach
    void tearDown() {
        engine.stopAudioOutput();
        engine.stop();
    }

    @Test
    void noConfiguredBackendIsNotAvailable() {
        assertThat(reasonOf(catchThrowable(engine::startAudioOutput)))
                .isEqualTo(StreamOpenRefusedException.Reason.NOT_AVAILABLE);
    }

    @Test
    void aBackendThatCannotStreamOnThisHostIsNotAvailable() {
        SynchronousTestBackend silent = new SynchronousTestBackend("Silent");
        silent.streamingSupported = false;
        engine.setStreamingProvision(provisionOf(silent));

        assertThat(reasonOf(catchThrowable(engine::startAudioOutput)))
                .isEqualTo(StreamOpenRefusedException.Reason.NOT_AVAILABLE);
    }

    @Test
    void aNegotiatedShapeTheEngineCannotRenderIsAnUnusableFormat() {
        SynchronousTestBackend widening = new SynchronousTestBackend("Widening");
        widening.negotiateToChannels = 4;
        engine.setStreamingProvision(provisionOf(widening));

        assertThat(reasonOf(catchThrowable(engine::startAudioOutput)))
                .isEqualTo(StreamOpenRefusedException.Reason.UNUSABLE_FORMAT);
    }

    @Test
    void aRetainedHandleWhoseCloseFailsAgainIsAPreviousStreamNotReleased() {
        SynchronousTestBackend backend = new SynchronousTestBackend("Holding");
        engine.setStreamingProvision(provisionOf(backend));
        engine.startAudioOutput();
        backend.failOnClose = true;
        engine.stopAudioOutput();

        Throwable refusal = catchThrowable(engine::startAudioOutput);

        assertThat(refusal).as("premise: the close failed again").hasMessageContaining("could not be released");
        assertThat(reasonOf(refusal)).isEqualTo(StreamOpenRefusedException.Reason.PREVIOUS_STREAM_NOT_RELEASED);
        backend.failOnClose = false;
    }

    private static StreamOpenRefusedException.Reason reasonOf(Throwable refusal) {
        assertThat(refusal).isInstanceOf(StreamOpenRefusedException.class);
        return ((StreamOpenRefusedException) refusal).reason();
    }

    private static StreamingProvision provisionOf(SynchronousTestBackend backend) {
        return new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name()))));
    }
}
