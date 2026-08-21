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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 316 — the Windows streaming proof (the story's Goals&nbsp;— Tests
 * item&nbsp;5): with ASIO provisioned as the engine's streaming path, Play on
 * a bound project actually streams through the real installed ASIO driver —
 * the engine opens {@link AsioBackend} via its ladder, the engine-owned
 * render pump cycles {@code processBlock} at device pace over the story-311
 * {@code bufferSwitch} bridge, and the transport position advances under
 * that drive. Then everything stops and closes cleanly.
 *
 * <p>Where {@link AsioStreamingIntegrationTest} proves the raw
 * backend-level {@code open → bufferSwitch → close} path (story 311), this
 * test proves the story-316 <em>production caller</em> on the primary
 * platform: {@code StreamingProvision → startAudioOutput() →
 * EngineStreamPump → processBlock → sink}, with honest
 * {@link AudioEngine#openStreamBackendName()} reporting.</p>
 *
 * <p>Gating, in order (copied from {@link AsioStreamingIntegrationTest} so
 * both suites gate identically): Windows only (the primary platform — the
 * test never assumes a non-Windows environment), then the
 * {@code requireOrAssumeAsioshim()} pattern (hard failure when
 * {@code DAW_REQUIRE_ASIOSHIM=1}, a clean skip otherwise), then a skip when
 * no ASIO driver is installed on the machine.</p>
 */
@EnabledOnOs(OS.WINDOWS)
class AsioEngineStreamingIntegrationTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int CHANNELS = 2;
    private static final int BIT_DEPTH = 32;
    private static final double TEMPO = 120.0;

    /**
     * Outer budget for the transport to visibly advance under pump drive.
     * Generous by design: at the portable preferred buffer size the first
     * rendered block lands within ~10&nbsp;ms of ASIOStart, so 5&nbsp;s
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
            // (or the 48 kHz stereo request) outright. Skipping keeps the
            // lane honest instead of failing on hardware the story does not
            // control — mirroring AsioStreamingIntegrationTest.
            assumeTrue(false, "ASIO driver rejected the engine's open: "
                    + rejected.getMessage());
            return;
        }

        try {
            // The ladder's one rung opened for real, and the engine's truth
            // queries report the OPEN stream — by construction (§2.4).
            assertThat(engine.isStreamOpen()).isTrue();
            assertThat(engine.openStreamBackendName()).contains(AsioBackend.NAME);
            assertThat(engine.openStreamDevice()).contains(device);
            assertThat(backend.isOpen()).isTrue();

            // Play. The RT clock is claimed, so the position can only move
            // when the render pump cycles processBlock — an advance IS the
            // proof that blocks stream at device pace over bufferSwitch.
            transport.play();
            double before = transport.getPositionInBeats();
            awaitTransportAdvance(transport, before);
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
     * Awaits (never blind-sleeps) the transport position moving past
     * {@code before} within {@link #ADVANCE_BUDGET}.
     */
    private static void awaitTransportAdvance(Transport transport, double before)
            throws InterruptedException {
        long deadline = System.nanoTime() + ADVANCE_BUDGET.toNanos();
        while (System.nanoTime() < deadline) {
            if (transport.getPositionInBeats() > before) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        assertThat(transport.getPositionInBeats())
                .as("the transport position must advance under the render "
                        + "pump's processBlock drive within "
                        + ADVANCE_BUDGET.toSeconds() + " s of play()")
                .isGreaterThan(before);
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
