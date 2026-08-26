package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AsioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 316 — the Windows streaming proof (the story's Goals&nbsp;— Tests
 * item&nbsp;5): with ASIO provisioned as the engine's streaming path, Play on
 * a bound project actually streams through the real installed ASIO driver —
 * the engine opens {@link AsioBackend} via its ladder, the engine-owned
 * render pump cycles {@code processBlock} at device pace over the story-311
 * {@code bufferSwitch} bridge, the transport position advances under that
 * drive, and the driver's own callback drains at least one FURTHER rendered
 * block after Play than it had already taken
 * ({@link AsioBackend#renderedBlocksConsumedByDriver()} strictly increasing
 * past a snapshot taken immediately before {@code play()}). Then everything
 * stops and closes cleanly.
 *
 * <p>Where {@link AsioStreamingIntegrationTest} proves the raw
 * backend-level {@code open → bufferSwitch → close} path (story 311), this
 * test proves the story-316 <em>production caller</em> on the primary
 * platform: {@code StreamingProvision → startAudioOutput() →
 * EngineStreamPump → processBlock → sink → bufferSwitch}, with honest
 * {@link AudioEngine#openStreamBackendName()} reporting. Transport advance
 * alone is NOT that proof (story 316 review): the pump advances the
 * transport before it sinks, and its capacity wait times out after one
 * block period whether or not a device callback ever arrives, so the
 * advance would also pass with a dead output bridge — the consumed-block
 * counter is what ties the advance to the hardware. Conversely the counter
 * alone is not the proof either: the pump sinks from
 * {@code startAudioOutput()} onward regardless of transport state, so the
 * counter may already be non-zero at {@code play()} — hence the strict
 * increase past the pre-{@code play()} snapshot rather than a bare
 * {@code >= 1}, and hence both assertions together.</p>
 *
 * <p>Gating, in order (copied from {@link AsioStreamingIntegrationTest} so
 * both suites gate identically): Windows only (the primary platform — the
 * test never assumes a non-Windows environment), then the
 * {@code requireOrAssumeAsioshim()} pattern (hard failure when
 * {@code DAW_REQUIRE_ASIOSHIM=1}, a clean skip otherwise), then a skip when
 * no ASIO driver is installed on the machine. A driver that REJECTS the
 * open is a skip on optional local runs only: under
 * {@code DAW_REQUIRE_ASIOSHIM=1} it is a hard failure (story 316 review —
 * on the required lane a regression that stops the production path from
 * opening must not leave CI green with no streaming proof).</p>
 */
@EnabledOnOs(OS.WINDOWS)
class AsioEngineStreamingIntegrationTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int CHANNELS = 2;
    private static final int BIT_DEPTH = 32;
    private static final double TEMPO = 120.0;

    /**
     * Outer budget for each post-{@code play()} proof: the transport
     * visibly advancing under pump drive, and the driver draining one more
     * rendered block. Generous by design: at the portable preferred buffer
     * size the next rendered block lands within ~10&nbsp;ms, so 5&nbsp;s
     * dominates every inner poll interval (house async-test rule: the guard
     * wait exceeds all inner waits; tolerances are never inflated instead).
     */
    private static final Duration ADVANCE_BUDGET = Duration.ofSeconds(5);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

    @Test
    void engineStreamsABoundProjectThroughAsioAndStopsCleanly() throws Exception {
        requireOrAssumeAsioshim();
        AsioBackend backend = new AsioBackend();
        assumeTrue(!backend.listDevices().isEmpty(), "no ASIO driver installed");

        DeviceId device = DeviceId.defaultFor(AsioBackend.NAME);
        // bufferSizeRange() can only reach ASIOGetBufferSize once a driver is
        // loaded, which open() is what does — so the first attempt uses the
        // portable DEFAULT_RANGE preferred value (same approach as
        // AsioStreamingIntegrationTest).
        int bufferFrames = backend.bufferSizeRange(device).preferred();
        AudioFormat format = new AudioFormat(SAMPLE_RATE, CHANNELS, BIT_DEPTH, bufferFrames);

        AudioEngine engine = new AudioEngine(format);
        engine.setStreamingProvision(new StreamingProvision(AsioBackend.NAME,
                List.of(new BackendStreamRung(backend, device))));

        DawProject project = buildSineProject(format);
        new EngineBinder(engine).bind(project);
        Transport transport = project.getTransport();

        try {
            engine.startAudioOutput();
        } catch (AudioBackendException rejected) {
            // An exotic driver may reject the portable default buffer size
            // (or the 48 kHz stereo request) outright. Since the story-316
            // review the engine's own supportsStreaming() guard lands here
            // too: a Windows host whose asioshim.dll exports the lifecycle
            // symbols but NOT the story-311 streaming ones fails the rung
            // before negotiation, so the single-rung ladder throws that
            // refusal rather than opening a stream whose sink discards.
            // On an optional local run, skipping keeps the lane honest
            // instead of failing on hardware (or a shim build) the story
            // does not control — mirroring AsioStreamingIntegrationTest.
            // On the required lane the open MUST succeed, so the rejection
            // is rethrown instead.
            skipUnlessAsioshimRequired(rejected);
            return;
        }

        try {
            // The ladder's one rung opened for real, and the engine's truth
            // queries report the OPEN stream — by construction (§2.4).
            assertThat(engine.isStreamOpen()).isTrue();
            assertThat(engine.openStreamBackendName()).contains(AsioBackend.NAME);
            assertThat(engine.openStreamDevice()).contains(device);
            assertThat(backend.isOpen()).isTrue();

            // The pump has been sinking since startAudioOutput(), so the
            // driver may already have drained blocks before Play. Snapshot
            // the counter FIRST so the assertion below is a strict increase
            // past whatever it had already taken, not a bare ">= 1". What
            // that proves is that the driver callback is STILL draining
            // engine-rendered blocks while the transport rolls — the bridge
            // is alive now, rather than having taken a few blocks at open
            // and then died (story 316 review). It does NOT claim the drain
            // is caused by play(): the pump sinks from startAudioOutput()
            // onward regardless of transport state, which is exactly why a
            // ">= 1" read at any point would have been satisfiable by a
            // long-dead bridge.
            long consumedBeforePlay = backend.renderedBlocksConsumedByDriver();

            // Play. The RT clock is claimed, so the position can only move
            // when the render pump cycles processBlock — an advance proves
            // the pump cycles. It does NOT prove the driver took the output:
            // the pump advances the transport before it sinks, and its
            // capacity wait times out after one block period even with a
            // dead callback. The consumed-block counter is what proves the
            // real bufferSwitch drained what the pump rendered.
            //
            // Only the counter above is a genuine pre-play snapshot; the
            // position below is read immediately AFTER play() returns, by
            // which time the pump may already have advanced a block or two.
            // That only makes its strict-increase assertion stricter, so it
            // is left where it is — hence the name. What both readings DO
            // share is that they are taken, and awaited, before the close in
            // the finally block: close() drops the bridge and the counter
            // query reads 0 from then on.
            transport.play();
            double positionAtPlay = transport.getPositionInBeats();

            awaitStrictIncreasePast(transport::getPositionInBeats, positionAtPlay,
                    "the transport position must advance under the render pump's "
                            + "processBlock drive");
            awaitStrictIncreasePast(backend::renderedBlocksConsumedByDriver,
                    consumedBeforePlay,
                    "the real ASIO bufferSwitch must drain at least one FURTHER "
                            + "engine-rendered block after play() (it had taken "
                            + consumedBeforePlay + " when play() was called) — "
                            + "transport advance alone does not prove the output "
                            + "bridge is live");
        } finally {
            transport.stop();
            engine.stopAudioOutput();
            engine.stop();
        }

        // Clean close: the engine forgot the stream and the driver released
        // its handle; a second close stays a safe no-op.
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(engine.openStreamBackendName()).isEmpty();
        assertThat(backend.isOpen()).isFalse();
        assertThatCode(backend::close)
                .as("a second close() must be a safe no-op")
                .doesNotThrowAnyException();
    }

    /**
     * Awaits (never blind-sleeps) {@code reading} moving STRICTLY past the
     * {@code baseline} sampled by the caller, within
     * {@link #ADVANCE_BUDGET}. One helper for both proofs — the transport
     * position and {@link AsioBackend#renderedBlocksConsumedByDriver()},
     * which moves only on a callback that consumed a sunk block, never on
     * one that emitted silence — so both share the one guard budget, which
     * dominates {@link #POLL_INTERVAL} and every other inner wait by the
     * same margin; no tolerance is ever inflated instead.
     *
     * <p>{@code reading} is a {@link DoubleSupplier} so the {@code long}
     * block counter and the {@code double} beat position use the same loop.
     * The counter is an ABSOLUTE count of consumed blocks since the stream
     * opened — not a windowed one — so the widening is justified by its
     * growth rate rather than by this method's budget: one increment per
     * driver callback, a few thousand per second at the fastest ASIO buffer
     * sizes, means 2^53 is millions of years of unbroken streaming away.
     * Every value either reading can produce here is therefore exactly
     * representable as a {@code double}, and the comparison is exact.</p>
     */
    private static void awaitStrictIncreasePast(
            DoubleSupplier reading, double baseline, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + ADVANCE_BUDGET.toNanos();
        while (System.nanoTime() < deadline) {
            if (reading.getAsDouble() > baseline) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        assertThat(reading.getAsDouble())
                .as(what + " within " + ADVANCE_BUDGET.toSeconds() + " s of play()")
                .isGreaterThan(baseline);
    }

    /**
     * Minimal playable project (the EngineBinderPlaybackTest construction):
     * transport + mixer come with the project; one audio track carries a
     * quiet 440 Hz sine clip so the streamed blocks are genuinely audible
     * on the device under test.
     */
    private static DawProject buildSineProject(AudioFormat format) {
        DawProject project = new DawProject("AsioStreaming", format);
        project.getTransport().setTempo(TEMPO);

        Track track = project.createAudioTrack("Lead");
        double lengthBeats = 8.0;
        AudioClip clip = new AudioClip("Clip", 0.0, lengthBeats, null);
        double samplesPerBeat = format.sampleRate() * 60.0 / TEMPO;
        int samples = (int) (samplesPerBeat * lengthBeats);
        float[][] data = new float[CHANNELS][samples];
        for (int i = 0; i < samples; i++) {
            float v = (float) Math.sin(2.0 * Math.PI * 440.0 * i / format.sampleRate()) * 0.2f;
            for (int ch = 0; ch < CHANNELS; ch++) {
                data[ch][i] = v;
            }
        }
        clip.setAudioData(data);
        track.addClip(clip);
        return project;
    }

    /**
     * Story 316 review — an open the real driver rejected is a clean skip
     * on an optional local run (hardware the story does not control), but
     * under {@code DAW_REQUIRE_ASIOSHIM=1} it is rethrown as a hard failure:
     * converting every open failure into a skip would let a regression that
     * prevents the production path from opening leave the required lane
     * green with no streaming proof. Copied verbatim into
     * {@link AsioStreamingIntegrationTest} so both suites gate identically.
     */
    private static void skipUnlessAsioshimRequired(AudioBackendException rejected) {
        if ("1".equals(System.getenv("DAW_REQUIRE_ASIOSHIM"))) {
            throw rejected;
        }
        assumeTrue(false, "ASIO driver rejected the engine's open: "
                + rejected.getMessage());
    }

    /**
     * Story 224 — gates the dedicated Windows-with-shim CI lane (the
     * {@code windows-asioshim.yml} workflow exports
     * {@code DAW_REQUIRE_ASIOSHIM=1}). Copied verbatim from
     * {@code NativeLibraryDetectorTest} so all three suites gate
     * identically.
     */
    private static void requireOrAssumeAsioshim() {
        boolean available = NativeLibraryDetector.isAvailable("asioshim");
        if ("1".equals(System.getenv("DAW_REQUIRE_ASIOSHIM"))) {
            assertThat(available)
                    .as("DAW_REQUIRE_ASIOSHIM=1 — asioshim.dll must be on the "
                            + "FFM library path. Ensure the native build ran "
                            + "with -DASIO_SDK_DIR=... before mvn verify.")
                    .isTrue();
        } else {
            assumeTrue(available,
                    "asioshim.dll not on java.library.path — skip "
                            + "(build the native libs with -DASIO_SDK_DIR=...; "
                            + "set DAW_REQUIRE_ASIOSHIM=1 to fail instead of skip)");
        }
    }
}
