package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.BackendFallbackEvent;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 316 — "ASIO as the Production Streaming Path" binding acceptance
 * tests (the story's Goals&nbsp;— Tests items 1–4; item 5 is the Windows
 * integration proof and lives in daw-core's ASIO integration suite):
 *
 * <ol>
 *   <li><b>Production caller</b> — selecting "ASIO" makes the engine
 *       actually {@code open()} the SDK backend with the resolved device
 *       identity and configured buffer size; the render pump drives
 *       {@code processBlock} output into {@code sink()} and captured
 *       {@code inputBlocks()} reach {@code processBlock}'s input planes.</li>
 *   <li><b>Device persistence</b> — Play → Stop → Play reopens the SAME
 *       configured device; no API accepts a bare device index any more.</li>
 *   <li><b>Honest reporting</b> — when the requested rung fails to open,
 *       the reported active backend is the rung that actually opened AND a
 *       {@link BackendFallbackEvent} is published on the EventBus.</li>
 *   <li><b>Reconfigure seam</b> — switching backend or buffer size closes
 *       the old stream BEFORE opening the new one, through the engine's one
 *       open/close seam; no orphan stream survives.</li>
 * </ol>
 *
 * <p>Uses a spy {@link AudioBackend} implemented directly against the
 * (unsealed, story 316) SDK interface: its {@code awaitSinkCapacity} parks
 * only briefly so the pump paces fast without wall-clock coupling, and its
 * {@code sink} is a counter — no real hardware is ever touched.</p>
 */
class Story316StreamingSeamTest {

    private static final long AWAIT_SECONDS = 5;

    // ── Story test 1: production caller ─────────────────────────────────────

    @Test
    void asioSelectionOpensBackendAndStreamsRenderAndCapture(@TempDir Path projectRoot)
            throws Exception {
        SpyStreamingBackend spy = new SpyStreamingBackend("ASIO");
        AudioBackendSelector selector = new AudioBackendSelector(Map.of("ASIO", () -> spy));
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 24, 64));
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);
        try {
            controller.applyConfiguration(new AudioEngineController.Request(
                    "ASIO", "", "Dev B", SampleRate.HZ_48000, 64, 24, 1));

            CountDownLatch inputReachedProcessBlock = new CountDownLatch(1);
            engine.setRecordingCallback((inputBuffer, numFrames) -> {
                for (int frame = 0; frame < numFrames; frame++) {
                    if (inputBuffer[0][frame] == 0.5f) {
                        inputReachedProcessBlock.countDown();
                        return;
                    }
                }
            });

            // The production Play path (TransportController) calls exactly
            // this no-arg seam — the ladder walk opens the spy for real.
            engine.startAudioOutput();

            assertThat(spy.opens()).hasSize(1);
            SpyStreamingBackend.OpenRecord open = spy.opens().get(0);
            assertThat(open.device()).isEqualTo(new DeviceId("ASIO", "Dev B"));
            assertThat(open.format().sampleRate()).isEqualTo(48_000.0);
            assertThat(open.format().channels()).isEqualTo(2);
            assertThat(open.bufferFrames()).isEqualTo(64);

            // Render output flows into sink(): the pump must push blocks.
            assertThat(spy.awaitSunkBlocks(3, AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the engine-owned render pump must push processBlock "
                            + "output into the open backend's sink()")
                    .isTrue();

            // Capture flows the other way: a block published on the spy's
            // inputBlocks() must reach processBlock's input planes.
            float[] interleaved = new float[2 * 64];
            Arrays.fill(interleaved, 0.5f);
            spy.publishInput(new AudioBlock(48_000.0, 2, 64, interleaved));
            assertThat(inputReachedProcessBlock.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("captured inputBlocks() must reach processBlock's "
                            + "input planes via the render pump")
                    .isTrue();
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    // ── Story test 2: device persistence ────────────────────────────────────

    @Test
    void configuredDeviceIsHonouredOnEveryOpenAcrossPlayStopPlay(@TempDir Path projectRoot) {
        SpyStreamingBackend spy = new SpyStreamingBackend("ASIO");
        AudioBackendSelector selector = new AudioBackendSelector(Map.of("ASIO", () -> spy));
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 24, 64));
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);
        try {
            controller.applyConfiguration(new AudioEngineController.Request(
                    "ASIO", "", "Dev B", SampleRate.HZ_48000, 64, 24, 1));

            engine.startAudioOutput();
            engine.stopAudioOutput();
            engine.startAudioOutput();

            // The SECOND open resolved the configured device again — the
            // device identity is honoured on EVERY open, never re-derived
            // from an index-0 default.
            assertThat(spy.opens())
                    .extracting(SpyStreamingBackend.OpenRecord::device)
                    .containsExactly(
                            new DeviceId("ASIO", "Dev B"),
                            new DeviceId("ASIO", "Dev B"));

            // No code path can open by bare device index any more — the
            // index-taking overloads no longer exist (compile-level
            // guarantee, pinned reflectively so a regression is loud).
            assertThatThrownBy(() ->
                    AudioEngine.class.getMethod("startAudioOutput", int.class))
                    .isInstanceOf(NoSuchMethodException.class);
            assertThatThrownBy(() ->
                    AudioEngine.class.getMethod("startAudioInputOutput", int.class))
                    .isInstanceOf(NoSuchMethodException.class);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    // ── Story test 3: honest reporting + fallback event ─────────────────────

    @Test
    void fallbackReportsActiveRungAndPublishesBackendFallbackEvent(@TempDir Path projectRoot)
            throws Exception {
        SpyStreamingBackend failing = new SpyStreamingBackend("ASIO");
        failing.failOpensWith(new AudioBackendException("ASIO driver refused the open"));
        SpyStreamingBackend fallback = new SpyStreamingBackend("SpyFallback");
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 24, 64));
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot));
        // A hand-built two-rung provision keeps the fallback rung a spy too:
        // no real hardware, fully deterministic ladder.
        engine.setStreamingProvision(new StreamingProvision("ASIO", List.of(
                new BackendStreamRung(failing, new DeviceId("ASIO", "Dev B")),
                new BackendStreamRung(fallback, DeviceId.defaultFor("SpyFallback")))));

        EventBus previousBus = EventBusPublisher.getDefault();
        DefaultEventBus bus = new DefaultEventBus();
        List<BackendFallbackEvent> events =
                Collections.synchronizedList(new ArrayList<>());
        CountDownLatch published = new CountDownLatch(1);
        bus.on(BackendFallbackEvent.class, event -> {
            events.add(event);
            published.countDown();
        });
        EventBusPublisher.setDefault(bus);
        try {
            engine.startAudioOutput();

            // Honest reporting: the reported active backend is — by
            // construction — the OPEN stream's, i.e. the rung that opened.
            assertThat(controller.getActiveBackendName()).isEqualTo("SpyFallback");
            assertThat(engine.openStreamBackendName()).contains("SpyFallback");
            assertThat(engine.openStreamDevice())
                    .contains(DeviceId.defaultFor("SpyFallback"));
            assertThat(failing.isOpen()).isFalse();
            assertThat(fallback.isOpen()).isTrue();

            // And the failed hop was published on the EventBus.
            assertThat(published.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("every failed ladder hop must publish a "
                            + "BackendFallbackEvent on the EventBus")
                    .isTrue();
            BackendFallbackEvent event = events.get(0);
            assertThat(event.requestedBackend()).isEqualTo("ASIO");
            assertThat(event.requestedDevice()).isEqualTo("Dev B");
            assertThat(event.activeBackend()).isEqualTo("SpyFallback");
            assertThat(event.activeDevice())
                    .isEqualTo(DeviceId.defaultFor("SpyFallback").name());
            assertThat(event.cause()).contains("refused");
        } finally {
            EventBusPublisher.setDefault(previousBus);
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
            bus.close();
        }
    }

    // ── Story test 4: reconfigure seam ──────────────────────────────────────

    @Test
    void reconfigureClosesOldStreamBeforeOpeningNewThroughTheOneSeam(@TempDir Path projectRoot) {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        SpyStreamingBackend spyA = new SpyStreamingBackend("SpyA", log);
        SpyStreamingBackend spyB = new SpyStreamingBackend("SpyB", log);
        AudioBackendSelector selector = new AudioBackendSelector(
                Map.of("SpyA", () -> spyA, "SpyB", () -> spyB));
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 24, 64));
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);
        try {
            controller.applyConfiguration(new AudioEngineController.Request(
                    "SpyA", "", "Out A", SampleRate.HZ_48000, 64, 24, 1));
            engine.startAudioOutput();
            assertThat(spyA.isOpen()).isTrue();

            // Backend switch: the old stream must be closed BEFORE the new
            // backend's open — no window with two live streams.
            controller.applyConfiguration(new AudioEngineController.Request(
                    "SpyB", "", "Out B", SampleRate.HZ_48000, 64, 24, 1));

            int firstCloseA = log.indexOf("close:SpyA");
            int firstOpenB = log.indexOf("open:SpyB");
            assertThat(firstCloseA)
                    .as("the outgoing SpyA stream must have been closed")
                    .isNotNegative();
            assertThat(firstOpenB)
                    .as("the incoming SpyB stream must have been opened "
                            + "(the stream was open before the reconfigure)")
                    .isNotNegative();
            assertThat(firstCloseA)
                    .as("close of the old stream must precede open of the new one")
                    .isLessThan(firstOpenB);
            assertThat(spyA.isOpen())
                    .as("no orphan stream: the old backend must not remain open")
                    .isFalse();
            assertThat(spyB.isOpen()).isTrue();
            assertThat(controller.getActiveBackendName()).isEqualTo("SpyB");

            // Buffer-size change on ONE backend: same seam, same ordering —
            // close the running stream, then reopen at the new size.
            int marker = log.size();
            controller.applyConfiguration(new AudioEngineController.Request(
                    "SpyB", "", "Out B", SampleRate.HZ_48000, 128, 24, 1));
            List<String> afterResize = List.copyOf(log).subList(marker, log.size());
            int closeB = afterResize.indexOf("close:SpyB");
            int openB = afterResize.indexOf("open:SpyB");
            assertThat(closeB).isNotNegative();
            assertThat(openB).isNotNegative();
            assertThat(closeB)
                    .as("a buffer-size change must close the old stream "
                            + "before reopening it")
                    .isLessThan(openB);
            assertThat(spyB.isOpen()).isTrue();
            List<SpyStreamingBackend.OpenRecord> opensB = spyB.opens();
            assertThat(opensB.get(opensB.size() - 1).bufferFrames()).isEqualTo(128);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

}
