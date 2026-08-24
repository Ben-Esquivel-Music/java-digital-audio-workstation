package com.benesquivelmusic.daw.core.audio.portaudio;

import com.benesquivelmusic.daw.sdk.audio.AudioStreamCallback;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
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
     * exists to remove.
     *
     * <p>The claim under test is an LP64 one, and it stays reachable there:
     * on Linux and macOS PortAudio's C `unsigned long` really is 64 bits
     * wide, so a host can hand the callback any long at all and the overflow
     * guard is load-bearing. On an LLP64 host the same parameter is 32 bits
     * and reaches {@code invoke} through {@code invokeNarrowLong}, so it
     * cannot exceed 4294967295 there and the guard cannot fire — which is why
     * this test drives {@code invoke} directly rather than pretending the
     * value could arrive from a Windows driver.</p>
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
     * The other unrepresentable claim: on an LP64 host, PortAudio's C
     * {@code unsigned long} with its top bit set reads back in Java as a
     * negative {@code long}. It is not a period we can act on, so it is read
     * as zero frames — and, as with every other bad claim, it must not throw
     * out of the upcall.
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

    /**
     * Story 316 re-review, the ABI half of the defect: the upcall descriptor
     * must declare PortAudio's {@code unsigned long} as the platform's C
     * {@code long}, not as a 64-bit {@code JAVA_LONG}.
     *
     * <p>Under LLP64 that C type is 32 bits. A descriptor that asks for 64
     * makes the upcall read a full 64-bit argument slot where the host wrote
     * only the lower 32 bits, and nothing in the ABI obliges the caller to
     * have zeroed the rest — so {@code frameCount} can arrive carrying bits
     * nobody supplied, and {@code invoke} multiplies it by the frame stride
     * to size a {@code reinterpret}. That is how a declaration turns into a
     * zero-fill past the driver's real buffer.</p>
     *
     * <p>Deliberately free of any {@code assumeTrue(os)}: it asserts that the
     * descriptor matches whatever the LINKER on this host calls a C
     * {@code long}, which is a true and non-vacuous statement on LP64 and
     * LLP64 alike.</p>
     */
    @Test
    void shouldDeclareTheCallbackWithThePlatformsCanonicalCLong() {
        MemoryLayout canonicalCLong = Linker.nativeLinker().canonicalLayouts().get("long");
        FunctionDescriptor descriptor = PortAudioBackend.callbackDescriptor();

        assertThat(descriptor.argumentLayouts().get(2))
                .as("frameCount is PortAudio's C `unsigned long` — 32 bits under LLP64, "
                        + "so its width is the platform's and not a constant")
                .isEqualTo(canonicalCLong);
        assertThat(descriptor.argumentLayouts().get(4))
                .as("PaStreamCallbackFlags is a typedef of the same C `unsigned long`")
                .isEqualTo(canonicalCLong);
    }

    /**
     * The other half, and the direct regression test for the finding: the
     * descriptor and the Java method the linker binds must agree.
     *
     * <p>{@code Linker.upcallStub} rejects a handle whose type differs from
     * the descriptor's, so a disagreement here is not a subtle miscount — it
     * is a failure to open a stream at all, on one platform, discovered by
     * whoever is running the DAW rather than by the build. Asserting the
     * bound handle's type against {@code callbackDescriptor().toMethodType()}
     * checks exactly that agreement, on whichever ABI the test happens to run
     * on.</p>
     */
    @Test
    void shouldBindTheCallbackEntryPointThatMatchesTheDescriptor() {
        RecordingCallback callback = new RecordingCallback((out, numFrames) -> { });
        PortAudioBackend.CallbackInvoker invoker = PortAudioBackend.CallbackInvoker
                .forStream(callback, 2, 2, 64);

        MethodHandle handle = PortAudioBackend.CallbackBridge.createHandle(invoker);

        assertThat(handle.type())
                .as("the upcall stub binds this handle against callbackDescriptor(); if "
                        + "the two disagree about the width of C `long`, Linker.upcallStub "
                        + "throws and no stream opens on this platform")
                .isEqualTo(PortAudioBackend.callbackDescriptor().toMethodType());
    }

    /**
     * The LLP64 entry point must ZERO-extend the host's unsigned frame count,
     * not sign-extend it.
     *
     * <p>{@code 0xFFFFFFFF} is the discriminator. Read as unsigned it is
     * 4294967295 — an absurdly oversized period, which the bridge clamps to
     * the opened buffer size and counts. Read with a widening cast it is
     * {@code -1}, which {@code Math.max(0L, frameCount)} turns into zero
     * frames: a silent dropout, no clamp recorded, and nothing anywhere
     * saying the driver misbehaved. So the assertions below distinguish the
     * correct conversion from the plausible wrong one rather than merely
     * observing that nothing threw.</p>
     *
     * <p>{@code output} is {@link MemorySegment#NULL} on purpose. With a real
     * output segment the bridge would trust the host's claim and silence the
     * frames past the ones it rendered — 4294967295 frames' worth of stride —
     * and that write is precisely the memory corruption this whole change
     * exists to prevent. A test must not perform it to prove it is possible.
     * The input side is a real segment, so the de-interleave still runs
     * against the clamped count.</p>
     */
    @Test
    void narrowEntryPointShouldZeroExtendAnUnsignedFrameCount() {
        int framesPerBuffer = 64;
        int channels = 2;
        RecordingCallback callback = new RecordingCallback(
                (out, numFrames) -> fill(out, numFrames, RENDERED));
        PortAudioBackend.CallbackInvoker invoker = PortAudioBackend.CallbackInvoker
                .forStream(callback, channels, channels, framesPerBuffer);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = allocateFrames(arena, framesPerBuffer, channels);

            int result = invoker.invokeNarrowLong(input, MemorySegment.NULL, 0xFFFFFFFF,
                    MemorySegment.NULL, 0, MemorySegment.NULL);

            assertThat(result)
                    .as("an absurd period must not throw out of the upcall")
                    .isEqualTo(PortAudioBindings.PA_CONTINUE);
            assertThat(callback.frameCounts)
                    .as("0xFFFFFFFF is 4294967295 frames, clamped to the %d the planes "
                            + "hold; sign extension would have made it -1, hence zero "
                            + "frames, and this list would read [0]", framesPerBuffer)
                    .containsExactly(framesPerBuffer);
            assertThat(invoker.clampedOversizedPeriods())
                    .as("the oversized period must be COUNTED; a sign-extended -1 is not "
                            + "greater than framesPerBuffer, so it would leave this at 0 "
                            + "and closeStream() would never report the misbehaving host")
                    .isEqualTo(1L);
        }
    }

    /**
     * The narrow entry point is an ABI adapter and nothing else: for a period
     * both signatures can express, it must produce byte-for-byte the same
     * result as calling {@code invoke} directly.
     *
     * <p>Two invokers, two identically pre-filled host buffers, one shared
     * input ramp, one call each. Anything that made {@code invokeNarrowLong} a
     * second implementation — a differently ordered loop, a forgotten clamp, a
     * missing silencing fill — separates the two buffers here.</p>
     */
    @Test
    void narrowEntryPointShouldRenderIdenticallyToTheWideOne() {
        int framesPerBuffer = 64;
        int channels = 2;
        RecordingCallback wideCallback = new RecordingCallback(
                (out, numFrames) -> fill(out, numFrames, RENDERED));
        RecordingCallback narrowCallback = new RecordingCallback(
                (out, numFrames) -> fill(out, numFrames, RENDERED));
        PortAudioBackend.CallbackInvoker wide = PortAudioBackend.CallbackInvoker
                .forStream(wideCallback, channels, channels, framesPerBuffer);
        PortAudioBackend.CallbackInvoker narrow = PortAudioBackend.CallbackInvoker
                .forStream(narrowCallback, channels, channels, framesPerBuffer);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = allocateFrames(arena, framesPerBuffer, channels);
            for (int frame = 0; frame < framesPerBuffer; frame++) {
                for (int channel = 0; channel < channels; channel++) {
                    input.setAtIndex(ValueLayout.JAVA_FLOAT,
                            (long) frame * channels + channel, ramp(frame, channel));
                }
            }
            MemorySegment wideOutput = allocateFrames(arena, framesPerBuffer, channels);
            MemorySegment narrowOutput = allocateFrames(arena, framesPerBuffer, channels);
            fillNative(wideOutput, framesPerBuffer, channels, STALE_MARKER);
            fillNative(narrowOutput, framesPerBuffer, channels, STALE_MARKER);

            int wideResult = wide.invoke(input, wideOutput, framesPerBuffer,
                    MemorySegment.NULL, 0L, MemorySegment.NULL);
            int narrowResult = narrow.invokeNarrowLong(input, narrowOutput, framesPerBuffer,
                    MemorySegment.NULL, 0, MemorySegment.NULL);

            assertThat(narrowResult).isEqualTo(wideResult);
            assertThat(readFrames(narrowOutput, 0, framesPerBuffer, channels))
                    .as("the narrow entry point must be a pure ABI adapter — same frames "
                            + "out, byte for byte, as the wide one")
                    .containsExactly(readFrames(wideOutput, 0, framesPerBuffer, channels));
            assertThat(narrowCallback.frameCounts)
                    .as("and the same frame count handed to the Java callback")
                    .isEqualTo(wideCallback.frameCounts);
            assertThat(narrowCallback.capturedInput)
                    .as("and the same de-interleaved input planes")
                    .isDeepEqualTo(wideCallback.capturedInput);
            assertThat(narrow.clampedOversizedPeriods())
                    .as("and the same clamp accounting")
                    .isEqualTo(wide.clampedOversizedPeriods());
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
