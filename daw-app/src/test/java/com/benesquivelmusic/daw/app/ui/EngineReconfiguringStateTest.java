package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.audio.performance.XrunDetector;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;
import com.benesquivelmusic.daw.sdk.audio.XrunEvent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RECONFIGURING on every Settings apply (story 338, ruling
 * E26): published once the record guard let the apply go ahead and before
 * the stream stops, followed by the apply's own terminal state; a declined
 * guard publishes nothing; a reconfigure refused whole still ends STOPPED.
 * The xrun publisher outlives each detector the apply rebuilds.
 */
@Tag("slow")
class EngineReconfiguringStateTest {

    private static final AudioEngineController.Request MOCK_AT_256 = new AudioEngineController.Request(
            MockAudioBackend.NAME, "", "", SampleRate.HZ_44100, 256, 16);

    private final MockAudioBackend mock = new MockAudioBackend();
    private final AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
    private final DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null,
            new RecordingNotifications(), new AudioBackendSelector(Map.of(MockAudioBackend.NAME, () -> mock)));

    @Test
    void anApplyWithNoStreamOpenIsReconfiguringThenStopped() {
        List<EngineStateChange> published = EngineHealthMonitorTest.subscribe(controller);
        try {
            controller.applyConfiguration(MOCK_AT_256);

            await(() -> published.size() == 2, "the apply's two states");
            assertThat(published).extracting(EngineStateChange::state)
                    .containsExactly(EngineState.RECONFIGURING, EngineState.STOPPED);
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void anApplyWithAStreamOpenIsReconfiguringThenRunning() {
        List<EngineStateChange> published = EngineHealthMonitorTest.subscribe(controller);
        try {
            controller.applyConfiguration(MOCK_AT_256);
            engine.startAudioOutput();

            controller.applyConfiguration(MOCK_AT_256);

            await(() -> published.size() == 4, "both applies' states");
            assertThat(published).extracting(EngineStateChange::state).containsExactly(
                    EngineState.RECONFIGURING, EngineState.STOPPED, EngineState.RECONFIGURING, EngineState.RUNNING);
            assertThat(published).extracting(EngineStateChange::cause)
                    .containsOnly(EngineStateChange.LossCause.NONE);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void aDeclinedRecordGuardPublishesNothing() {
        List<EngineStateChange> published = EngineHealthMonitorTest.subscribe(controller);
        controller.setRecordConfigurationGuard(
                () -> CompletableFuture.failedStage(new AudioConfigurationDeclinedException()));
        try {
            assertThatThrownBy(() -> controller.applyConfiguration(MOCK_AT_256))
                    .isInstanceOf(AudioConfigurationDeclinedException.class);

            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
            assertThat(published).as("no RECONFIGURING a declined apply would strand").isEmpty();
            assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
        } finally {
            controller.shutdown();
        }
    }

    /**
     * Story 338: {@code xrunEvents()} is one publisher for the
     * controller's life; each rebuilt detector offers into it and is handed
     * to the engine, whose next render pump ticks it.
     */
    @Test
    void theXrunPublisherOutlivesEachDetectorRebuildAndTheEngineTicksTheNewDetector() {
        Flow.Publisher<XrunEvent> before = controller.xrunEvents();
        List<XrunEvent> received = Collections.synchronizedList(new ArrayList<>());
        before.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(XrunEvent event) { received.add(event); }
            @Override public void onError(Throwable t) { /* ignore */ }
            @Override public void onComplete() { /* ignore */ }
        });
        try {
            assertThat(engine.getXrunDetector()).as("the constructor hands its detector to the engine")
                    .isSameAs(controller.getXrunDetector());
            XrunDetector first = controller.getXrunDetector();

            controller.applyConfiguration(MOCK_AT_256);

            assertThat(controller.getXrunDetector()).as("premise: the apply rebuilt the detector").isNotSameAs(first);
            assertThat(engine.getXrunDetector()).isSameAs(controller.getXrunDetector());
            assertThat(controller.xrunEvents()).isSameAs(before);
            controller.getXrunDetector().reportDropped(2);
            await(() -> !received.isEmpty(), "the rebuilt detector's event reaches the first subscriber");
            assertThat(received).hasSize(1).first().isInstanceOf(XrunEvent.BufferDropped.class);
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void aReconfigureRefusedWholeIsReconfiguringThenStopped() {
        SpyStreamingBackend wedging = new SpyStreamingBackend("Wedging");
        engine.setStreamingProvision(new StreamingProvision("Wedging",
                List.of(new BackendStreamRung(wedging, new DeviceId("Wedging", "Out")))));
        List<EngineStateChange> published = EngineHealthMonitorTest.subscribe(controller);
        try {
            engine.startAudioOutput();
            wedging.blockAwait = true;
            await(() -> wedging.blockedAwaitEntries.get() >= 1, "the render pump is wedged");

            assertThatThrownBy(() -> controller.applyConfiguration(MOCK_AT_256))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("render pump is still active");

            await(() -> published.size() == 2, "the refused apply's two states");
            assertThat(published).extracting(EngineStateChange::state)
                    .containsExactly(EngineState.RECONFIGURING, EngineState.STOPPED);
        } finally {
            wedging.blockAwait = false;
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }
}
