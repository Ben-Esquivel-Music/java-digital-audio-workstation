package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 316 — contract tests for the streaming seams added to
 * {@link AudioBackend}: the {@code supportsStreaming()} capability flag,
 * the {@code awaitSinkCapacity(long)} device-pacing seam, and the
 * {@code negotiateFormat(AudioFormat)} negotiation seam.
 *
 * <p>Timing assertions follow the house rule: the class-level guard budget
 * is far larger than any inner wait, minimum-park assertions use a generous
 * fraction of the requested timeout, and no assertion pins a tight upper
 * bound on a park.</p>
 */
@Timeout(30)
class AudioBackendStreamingContractTest {

    /** Requested park for the pacing tests — one generous "block period". */
    private static final long PARK_TIMEOUT_NANOS = 400_000_000L; // 400 ms

    /** Generous fraction of the timeout a real park must consume. */
    private static final long MIN_PARK_NANOS = 100_000_000L; // 100 ms

    /**
     * {@code LockSupport.parkNanos} is permitted to return spuriously, so a
     * single early return must not fail the test; a wake-up storm across
     * every attempt would.
     */
    private static final int PARK_ATTEMPTS = 3;

    // ------------------------------------------------------------------
    // supportsStreaming()
    // ------------------------------------------------------------------

    @Test
    void streamingBackendsReportSupportsStreaming() {
        assertTrue(new JavaxSoundBackend().supportsStreaming(),
                "Java Sound streams through SourceDataLine/TargetDataLine");
        assertTrue(new MockAudioBackend().supportsStreaming(),
                "the mock streams deterministically into assertable buffers");
    }

    /**
     * ASIO's flag is not a constant (story 316 review): it must answer from
     * a live probe of the native {@code asioshim}'s streaming symbols —
     * {@code true} on the {@code windows-asioshim.yml} lane where the DLL
     * exports them, {@code false} on a host without it, where {@code open()}
     * would degrade to the silent story-310 path and the selector must not
     * offer ASIO. Asserting a literal {@code true} here would be the exact
     * false success the gate exists to prevent.
     *
     * <p>Deriving the expected value from {@code new
     * AsioStreamingShim().isStreamingAvailable()} is no better, and is what
     * this test used to do: that is the very call
     * {@link AsioBackend#supportsStreaming()} makes, so the assertion holds
     * for <em>any</em> implementation of the method on <em>every</em> host —
     * a tautology, not a test. Both answers are pinned through the
     * package-private shim seam instead, which is host-independent and
     * fails the moment the flag stops tracking the probe. Only the
     * STREAMING shim is substituted: the driver / lifecycle shim stays the
     * production one, so this also pins that the capability is answered by
     * the streaming probe alone (where {@code AsioBackendStreamingTest}
     * asks the same question of a backend whose driver shim is stubbed
     * too).</p>
     */
    @Test
    void asioSupportsStreamingTracksTheStreamingShimProbeOnBothBranches() {
        try {
            AsioBackend.setStreamingShimFactory(() -> new ProbeStreamingShim(false));
            assertFalse(new AsioBackend().supportsStreaming(),
                    "an asioshim missing the story-311 streaming symbols must not be "
                            + "offered as a streaming backend: open() would degrade to "
                            + "the silent story-310 path");

            AsioBackend.setStreamingShimFactory(() -> new ProbeStreamingShim(true));
            assertTrue(new AsioBackend().supportsStreaming(),
                    "an asioshim exporting every story-311 streaming symbol makes ASIO "
                            + "a real streaming backend");
        } finally {
            AsioBackend.resetStreamingShimFactory();
        }
    }

    /**
     * Streaming shim whose availability probe answers a fixed value. Nothing
     * else is overridden — {@code supportsStreaming()} calls the probe and
     * closes the shim, and the inherited {@code close()} releases the real
     * superclass arena.
     */
    private static final class ProbeStreamingShim extends AsioStreamingShim {

        private final boolean streamingAvailable;

        ProbeStreamingShim(boolean streamingAvailable) {
            this.streamingAvailable = streamingAvailable;
        }

        @Override
        boolean isStreamingAvailable() {
            return streamingAvailable;
        }
    }

    @Test
    void nonStreamingBackendsInheritSupportsStreamingFalse() {
        assertFalse(new WasapiBackend().supportsStreaming(),
                "WASAPI sink discards by construction until its streaming story lands");
        assertFalse(new WasapiBackend(true).supportsStreaming(),
                "WASAPI exclusive mode is gated the same way");
        assertFalse(new CoreAudioBackend().supportsStreaming(),
                "CoreAudio sink discards by construction");
        assertFalse(new JackBackend().supportsStreaming(),
                "JACK sink discards by construction");
    }

    // ------------------------------------------------------------------
    // awaitSinkCapacity(long)
    // ------------------------------------------------------------------

    @Test
    void defaultAwaitSinkCapacityParksForTheTimeout() {
        // MockAudioBackend deliberately inherits the default full-timeout
        // park (wall-clock pacing keeps pump-driven tests from spinning).
        assertParksAtLeastMinFraction(new MockAudioBackend());
    }

    @Test
    void asioAwaitSinkCapacityFallsBackToDefaultParkWithNoOpenStream() {
        // No open() was ever issued, so there is no buffer-switch bridge to
        // poll — the override must degrade to the default full-timeout park.
        assertParksAtLeastMinFraction(new AsioBackend());
    }

    @Test
    void javaxSoundAwaitSinkCapacityReturnsImmediately() {
        // SourceDataLine.write IS this backend's pacing; awaitSinkCapacity is
        // a no-op. Ask for a 5 s park and assert we got nowhere near it —
        // the inherited default would consume the full 5 s and fail this.
        JavaxSoundBackend backend = new JavaxSoundBackend();
        long start = System.nanoTime();
        backend.awaitSinkCapacity(5_000_000_000L);
        long elapsed = System.nanoTime() - start;
        assertTrue(elapsed < 2_000_000_000L,
                "no-op override should return immediately, took " + elapsed + " ns");
    }

    /**
     * Asserts that at least one of a few {@code awaitSinkCapacity} calls
     * parks for a generous fraction of the requested timeout. Multiple
     * attempts absorb a (legal, rare) spurious {@code parkNanos} wake-up
     * without inflating the inner tolerance.
     */
    private static void assertParksAtLeastMinFraction(AudioBackend backend) {
        long longest = 0L;
        for (int attempt = 0; attempt < PARK_ATTEMPTS && longest < MIN_PARK_NANOS; attempt++) {
            long start = System.nanoTime();
            backend.awaitSinkCapacity(PARK_TIMEOUT_NANOS);
            longest = Math.max(longest, System.nanoTime() - start);
        }
        assertTrue(longest >= MIN_PARK_NANOS,
                "expected a park of at least " + MIN_PARK_NANOS
                        + " ns in " + PARK_ATTEMPTS + " attempts; longest was "
                        + longest + " ns");
    }

    // ------------------------------------------------------------------
    // negotiateFormat(AudioFormat)
    // ------------------------------------------------------------------

    @Test
    void defaultNegotiateFormatIsIdentity() {
        // MockAudioBackend inherits the default: the request comes back as-is.
        MockAudioBackend backend = new MockAudioBackend();
        AudioFormat requested = AudioFormat.STUDIO_QUALITY_48K;
        assertSame(requested, backend.negotiateFormat(requested));
    }

    @Test
    void javaxSoundNegotiateFormatClampsBitDepthTo16PreservingRateAndChannels() {
        JavaxSoundBackend backend = new JavaxSoundBackend();

        AudioFormat negotiated = backend.negotiateFormat(new AudioFormat(96_000.0, 4, 24));
        assertEquals(96_000.0, negotiated.sampleRate());
        assertEquals(4, negotiated.channels());
        assertEquals(16, negotiated.bitDepth());

        AudioFormat negotiated32 = backend.negotiateFormat(new AudioFormat(48_000.0, 2, 32));
        assertEquals(new AudioFormat(48_000.0, 2, 16), negotiated32);
    }

    @Test
    void javaxSoundNegotiateFormatPassesSixteenBitRequestsThroughUnchanged() {
        JavaxSoundBackend backend = new JavaxSoundBackend();
        AudioFormat requested = AudioFormat.CD_QUALITY; // already 16-bit
        assertSame(requested, backend.negotiateFormat(requested));
    }
}
