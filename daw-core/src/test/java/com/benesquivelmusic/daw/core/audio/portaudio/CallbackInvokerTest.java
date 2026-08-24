package com.benesquivelmusic.daw.core.audio.portaudio;

import com.benesquivelmusic.daw.sdk.audio.AudioStreamCallback;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link PortAudioBackend.CallbackInvoker} the way PortAudio's upcall
 * stub does — real native segments, a raw {@code frameCount}, and the
 * {@code timeInfo} / {@code statusFlags} / {@code userData} arguments the C
 * signature carries — because nothing else in the suite does.
 *
 * <p>The oversized-block clamp {@code CallbackBackendAdapter} already owns is
 * exercised by calling that adapter's fake callback directly, which means it
 * never reaches this bridge. The bridge, however, indexes the pre-allocated
 * channel planes FIRST: it de-interleaves into them and interleaves out of
 * them before {@code AudioStreamCallback.process} — and therefore before any
 * downstream clamp — is ever entered. A host period larger than the one the
 * stream was opened with used to throw {@link ArrayIndexOutOfBoundsException}
 * right there, out of an FFM upcall, on the driver's real-time thread.</p>
 */
class CallbackInvokerTest {

    private static final float STALE_MARKER = 0.5f;
    private static final float GUARD_MARKER = -7.5f;
    private static final float RENDERED = 0.25f;

    /**
     * Pins the defect: an oversized host period must be clamped at the
     * PortAudio bridge, not only in {@code CallbackBackendAdapter}, because
     * the bridge indexes the channel planes before the adapter sees anything.
     */
    @Test
    void shouldClampHostPeriodLargerThanTheOpenedBufferInsteadOfThrowing() {
        int framesPerBuffer = 64;
        int hostFrames = 128;
        int channels = 2;
        RecordingCallback callback = new RecordingCallback(
                (out, numFrames) -> fill(out, numFrames, RENDERED));
        PortAudioBackend.CallbackInvoker invoker = PortAudioBackend.CallbackInvoker
                .forStream(callback, channels, channels, framesPerBuffer);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = allocateFrames(arena, hostFrames, channels);
            MemorySegment output = allocateFrames(arena, hostFrames, channels);
            fillNative(output, hostFrames, channels, STALE_MARKER);

            int result = invoker.invoke(input, output, hostFrames,
                    MemorySegment.NULL, 0L, MemorySegment.NULL);

            assertThat(result)
                    .as("the bridge must tell PortAudio to keep streaming")
                    .isEqualTo(PortAudioBindings.PA_CONTINUE);
            assertThat(callback.frameCounts)
                    .as("the callback must be handed the CLAMPED frame count, never the "
                            + "host's oversized one — its planes are only %d long",
                            framesPerBuffer)
                    .containsExactly(framesPerBuffer);
            assertThat(callback.lastOutputPlaneLengths)
                    .as("every output plane stays at the opened buffer size")
                    .containsExactly(framesPerBuffer, framesPerBuffer);
            assertThat(callback.lastInputPlaneLengths)
                    .as("every input plane stays at the opened buffer size")
                    .containsExactly(framesPerBuffer, framesPerBuffer);

            assertThat(readFrames(output, 0, framesPerBuffer, channels))
                    .as("the frames the bridge could render must reach the host buffer")
                    .containsOnly(RENDERED);
            assertThat(readFrames(output, framesPerBuffer, hostFrames - framesPerBuffer, channels))
                    .as("the excess frames must be SILENCE, not the stale %f the host "
                            + "buffer was pre-filled with", STALE_MARKER)
                    .containsOnly(0.0f);
            assertThat(invoker.clampedOversizedPeriods())
                    .as("the clamp must be counted so closeStream() can report it")
                    .isEqualTo(1L);
        }
    }

    /**
     * No-regression guard for the ordinary case: PortAudio honours the
     * non-zero {@code framesPerBuffer} it was opened with, so nothing is
     * clamped and every host frame carries rendered audio.
     */
    @Test
    void shouldRenderEveryFrameWhenTheHostHonoursTheOpenedPeriod() {
        int framesPerBuffer = 64;
        int channels = 2;
        RecordingCallback callback = new RecordingCallback(
                (out, numFrames) -> fill(out, numFrames, RENDERED));
        PortAudioBackend.CallbackInvoker invoker = PortAudioBackend.CallbackInvoker
                .forStream(callback, channels, channels, framesPerBuffer);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = allocateFrames(arena, framesPerBuffer, channels);
            MemorySegment output = allocateFrames(arena, framesPerBuffer, channels);
            fillNative(output, framesPerBuffer, channels, STALE_MARKER);

            int result = invoker.invoke(input, output, framesPerBuffer,
                    MemorySegment.NULL, 0L, MemorySegment.NULL);

            assertThat(result).isEqualTo(PortAudioBindings.PA_CONTINUE);
            assertThat(callback.frameCounts).containsExactly(framesPerBuffer);
            assertThat(readFrames(output, 0, framesPerBuffer, channels))
                    .as("an exact period must be rendered in full — the clamp must not "
                            + "cost the common case a single frame")
                    .containsOnly(RENDERED);
            assertThat(invoker.clampedOversizedPeriods())
                    .as("an exact period is not a clamp")
                    .isZero();
        }
    }

    /**
     * The other side of the same defect: a SHORT host period must not be
     * padded up to the plane length, because the bridge would then write past
     * the end of the host's real buffer. A sentinel-filled guard region
     * immediately after the host buffer proves it does not.
     */
    @Test
    void shouldNotWritePastTheHostBufferWhenTheHostPeriodIsShort() {
        int framesPerBuffer = 64;
        int hostFrames = 32;
        int channels = 2;
        int guardFrames = 16;
        RecordingCallback callback = new RecordingCallback(
                (out, numFrames) -> fill(out, out[0].length, RENDERED));
        PortAudioBackend.CallbackInvoker invoker = PortAudioBackend.CallbackInvoker
                .forStream(callback, channels, channels, framesPerBuffer);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = allocateFrames(arena, hostFrames, channels);
            MemorySegment backing = allocateFrames(arena, hostFrames + guardFrames, channels);
            fillNative(backing, hostFrames + guardFrames, channels, GUARD_MARKER);
            // Hand the bridge ONLY the host's 32-frame prefix; the tail is the guard.
            MemorySegment output = backing.asSlice(0L, frameBytes(hostFrames, channels));

            int result = invoker.invoke(input, output, hostFrames,
                    MemorySegment.NULL, 0L, MemorySegment.NULL);

            assertThat(result).isEqualTo(PortAudioBindings.PA_CONTINUE);
            assertThat(callback.frameCounts)
                    .as("a short host period is the host's truth and must be passed through")
                    .containsExactly(hostFrames);
            assertThat(readFrames(backing, 0, hostFrames, channels))
                    .as("the host's own frames must carry the rendered value")
                    .containsOnly(RENDERED);
            assertThat(readFrames(backing, hostFrames, guardFrames, channels))
                    .as("the bridge must not write past the host's buffer even though its "
                            + "planes are %d frames long and the callback filled all of them",
                            framesPerBuffer)
                    .containsOnly(GUARD_MARKER);
            assertThat(invoker.clampedOversizedPeriods())
                    .as("a short period is not an oversized one")
                    .isZero();
        }
    }

    /**
     * The de-interleave side of the clamp: the bridge must read the host's
     * interleaved input into the planes, and — on an oversized period — must
     * read only as many frames as the planes can hold.
     */
    @Test
    void shouldDeinterleaveInputUpToTheClampedFrameCount() {
        int framesPerBuffer = 8;
        int hostFrames = 12;
        int channels = 2;
        RecordingCallback callback = new RecordingCallback((out, numFrames) -> { });
        PortAudioBackend.CallbackInvoker invoker = PortAudioBackend.CallbackInvoker
                .forStream(callback, channels, channels, framesPerBuffer);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = allocateFrames(arena, hostFrames, channels);
            for (int frame = 0; frame < hostFrames; frame++) {
                for (int channel = 0; channel < channels; channel++) {
                    input.setAtIndex(ValueLayout.JAVA_FLOAT,
                            (long) frame * channels + channel, ramp(frame, channel));
                }
            }
            MemorySegment output = allocateFrames(arena, hostFrames, channels);

            int result = invoker.invoke(input, output, hostFrames,
                    MemorySegment.NULL, 0L, MemorySegment.NULL);

            assertThat(result).isEqualTo(PortAudioBindings.PA_CONTINUE);
            assertThat(callback.capturedInput)
                    .as("input must be de-interleaved into one plane per channel")
                    .hasDimensions(channels, framesPerBuffer);
            for (int channel = 0; channel < channels; channel++) {
                for (int frame = 0; frame < framesPerBuffer; frame++) {
                    assertThat(callback.capturedInput[channel][frame])
                            .as("plane %d frame %d", channel, frame)
                            .isEqualTo(ramp(frame, channel));
                }
            }
            assertThat(invoker.clampedOversizedPeriods()).isEqualTo(1L);
        }
    }

    /**
     * PortAudio passes {@code NULL} for the direction a half-duplex stream
     * does not use. The bridge must survive that even when it was configured
     * with channels on that side — an exception here would unwind into C.
     */
    @Test
    void shouldTolerateNullInputAndNullOutputSegments() {
        int framesPerBuffer = 32;
        int channels = 2;
        RecordingCallback callback = new RecordingCallback(
                (out, numFrames) -> fill(out, numFrames, RENDERED));
        PortAudioBackend.CallbackInvoker invoker = PortAudioBackend.CallbackInvoker
                .forStream(callback, channels, channels, framesPerBuffer);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment real = allocateFrames(arena, framesPerBuffer, channels);

            int nullInput = invoker.invoke(MemorySegment.NULL, real, framesPerBuffer,
                    MemorySegment.NULL, 0L, MemorySegment.NULL);
            int nullOutput = invoker.invoke(real, MemorySegment.NULL, framesPerBuffer,
                    MemorySegment.NULL, 0L, MemorySegment.NULL);
            int bothNull = invoker.invoke(MemorySegment.NULL, MemorySegment.NULL,
                    framesPerBuffer, MemorySegment.NULL, 0L, MemorySegment.NULL);

            assertThat(nullInput).isEqualTo(PortAudioBindings.PA_CONTINUE);
            assertThat(nullOutput).isEqualTo(PortAudioBindings.PA_CONTINUE);
            assertThat(bothNull).isEqualTo(PortAudioBindings.PA_CONTINUE);
            assertThat(callback.frameCounts)
                    .as("the Java callback still runs for every period, NULL side or not")
                    .containsExactly(framesPerBuffer, framesPerBuffer, framesPerBuffer);
            assertThat(invoker.clampedOversizedPeriods()).isZero();
        }
    }

    /**
     * Pins the defect the clamp itself opened: sizing the output segment from
     * the host's raw frame count multiplies a value the driver controls. At
     * Long.MAX_VALUE that product overflows to a NEGATIVE byte count and
     * MemorySegment.reinterpret answers it with IllegalArgumentException —
     * back out of the upcall into C, the same unrecoverable failure the clamp
     * exists to remove. The upcall descriptor declares JAVA_LONG for
     * PortAudio's C `unsigned long`, a 32-bit type on Windows, so the high
     * half of that register is not architecturally guaranteed to be zero.
     */
    @Test
    void shouldSurviveAFrameCountWhoseByteSizeCannotBeRepresented() {
        int framesPerBuffer = 64;
        int channels = 2;
        int guardFrames = 16;
        RecordingCallback callback = new RecordingCallback(
                (out, numFrames) -> fill(out, out[0].length, RENDERED));
        PortAudioBackend.CallbackInvoker invoker = PortAudioBackend.CallbackInvoker
                .forStream(callback, channels, channels, framesPerBuffer);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = allocateFrames(arena, framesPerBuffer, channels);
            MemorySegment backing = allocateFrames(arena, framesPerBuffer + guardFrames, channels);
            fillNative(backing, framesPerBuffer + guardFrames, channels, GUARD_MARKER);
            // The host's real buffer is only framesPerBuffer long, whatever it claims.
            MemorySegment output = backing.asSlice(0L, frameBytes(framesPerBuffer, channels));

            int result = invoker.invoke(input, output, Long.MAX_VALUE,
                    MemorySegment.NULL, 0L, MemorySegment.NULL);

            assertThat(result)
                    .as("an unrepresentable period must not throw out of the upcall")
                    .isEqualTo(PortAudioBindings.PA_CONTINUE);
            assertThat(callback.frameCounts)
                    .as("the callback still sees the clamped count")
                    .containsExactly(framesPerBuffer);
            assertThat(readFrames(backing, 0, framesPerBuffer, channels))
                    .as("the frames we rendered must still reach the host buffer")
                    .containsOnly(RENDERED);
            assertThat(readFrames(backing, framesPerBuffer, guardFrames, channels))
                    .as("an unbelievable period must not be silenced into memory the host "
                            + "never described — the fill is skipped, not extrapolated")
                    .containsOnly(GUARD_MARKER);
            assertThat(invoker.clampedOversizedPeriods())
                    .as("it is still an oversized period, and still counted")
                    .isEqualTo(1L);
        }
    }

    /**
     * The other unrepresentable claim: PortAudio's C {@code unsigned long}
     * with its top bit set reads back in Java as a negative {@code long}. It
     * is not a period we can act on, so it is read as zero frames — and, as
     * with every other bad claim, it must not throw out of the upcall.
     */
    @Test
    void shouldTreatANegativeFrameCountAsZeroFrames() {
        int framesPerBuffer = 64;
        int channels = 2;
        RecordingCallback callback = new RecordingCallback(
                (out, numFrames) -> fill(out, out[0].length, RENDERED));
        PortAudioBackend.CallbackInvoker invoker = PortAudioBackend.CallbackInvoker
                .forStream(callback, channels, channels, framesPerBuffer);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = allocateFrames(arena, framesPerBuffer, channels);
            MemorySegment output = allocateFrames(arena, framesPerBuffer, channels);
            fillNative(output, framesPerBuffer, channels, GUARD_MARKER);

            int result = invoker.invoke(input, output, -1L,
                    MemorySegment.NULL, 0L, MemorySegment.NULL);

            assertThat(result).isEqualTo(PortAudioBindings.PA_CONTINUE);
            assertThat(callback.frameCounts)
                    .as("a negative period is zero frames, not a huge unsigned one")
                    .containsExactly(0);
            assertThat(readFrames(output, 0, framesPerBuffer, channels))
                    .as("zero frames means not one sample is written, even though the "
                            + "callback filled every plane")
                    .containsOnly(GUARD_MARKER);
            assertThat(invoker.clampedOversizedPeriods())
                    .as("zero frames is not an oversized period")
                    .isZero();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static float ramp(int frame, int channel) {
        return frame + channel * 0.5f;
    }

    private static long frameBytes(int frames, int channels) {
        return (long) frames * channels * Float.BYTES;
    }

    private static MemorySegment allocateFrames(Arena arena, int frames, int channels) {
        return arena.allocate(frameBytes(frames, channels), Float.BYTES);
    }

    private static void fillNative(MemorySegment segment, int frames, int channels, float value) {
        for (long index = 0; index < (long) frames * channels; index++) {
            segment.setAtIndex(ValueLayout.JAVA_FLOAT, index, value);
        }
    }

    private static float[] readFrames(MemorySegment segment, int firstFrame, int frames,
                                      int channels) {
        float[] values = new float[frames * channels];
        for (int i = 0; i < values.length; i++) {
            values[i] = segment.getAtIndex(ValueLayout.JAVA_FLOAT,
                    (long) firstFrame * channels + i);
        }
        return values;
    }

    private static void fill(float[][] planes, int frames, float value) {
        for (float[] plane : planes) {
            for (int frame = 0; frame < Math.min(frames, plane.length); frame++) {
                plane[frame] = value;
            }
        }
    }

    /** What a test wants written into the output planes for one period. */
    @FunctionalInterface
    private interface Renderer {
        void render(float[][] outputBuffer, int numFrames);
    }

    /** Records exactly what the bridge handed the Java callback. */
    private static final class RecordingCallback implements AudioStreamCallback {

        private final Renderer renderer;
        private final List<Integer> frameCounts = new ArrayList<>();
        private List<Integer> lastInputPlaneLengths = List.of();
        private List<Integer> lastOutputPlaneLengths = List.of();
        private float[][] capturedInput = new float[0][];

        private RecordingCallback(Renderer renderer) {
            this.renderer = renderer;
        }

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            frameCounts.add(numFrames);
            lastInputPlaneLengths = planeLengths(inputBuffer);
            lastOutputPlaneLengths = planeLengths(outputBuffer);
            capturedInput = copyOf(inputBuffer);
            renderer.render(outputBuffer, numFrames);
        }

        private static List<Integer> planeLengths(float[][] planes) {
            List<Integer> lengths = new ArrayList<>(planes.length);
            for (float[] plane : planes) {
                lengths.add(plane.length);
            }
            return lengths;
        }

        private static float[][] copyOf(float[][] planes) {
            float[][] copy = new float[planes.length][];
            for (int channel = 0; channel < planes.length; channel++) {
                copy[channel] = planes[channel].clone();
            }
            return copy;
        }
    }
}
