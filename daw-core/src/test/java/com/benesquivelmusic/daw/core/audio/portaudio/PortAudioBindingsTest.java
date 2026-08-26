package com.benesquivelmusic.daw.core.audio.portaudio;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.assertj.core.api.Assertions.assertThat;

class PortAudioBindingsTest {

    @Test
    void shouldReportAvailabilityBasedOnNativeLibrary() {
        // PortAudio is unlikely to be installed in the CI/test environment
        PortAudioBindings bindings = new PortAudioBindings();
        // We don't assert true/false — just that it doesn't crash
        assertThat(bindings.isAvailable()).isIn(true, false);
    }

    @Test
    void shouldDefineConstants() {
        assertThat(PortAudioBindings.PA_FLOAT32).isEqualTo(0x00000001L);
        assertThat(PortAudioBindings.PA_NO_ERROR).isEqualTo(0);
        assertThat(PortAudioBindings.PA_NO_DEVICE).isEqualTo(-1);
        assertThat(PortAudioBindings.PA_CONTINUE).isEqualTo(0);
        assertThat(PortAudioBindings.PA_COMPLETE).isEqualTo(1);
        assertThat(PortAudioBindings.PA_ABORT).isEqualTo(2);
    }

    @Test
    void shouldDefineDeviceInfoLayout() {
        assertThat(PortAudioBindings.PA_DEVICE_INFO_LAYOUT).isNotNull();
        assertThat(PortAudioBindings.PA_DEVICE_INFO_LAYOUT.byteSize()).isGreaterThan(0);
    }

    @Test
    void shouldDefineStreamInfoLayout() {
        assertThat(PortAudioBindings.PA_STREAM_INFO_LAYOUT).isNotNull();
        assertThat(PortAudioBindings.PA_STREAM_INFO_LAYOUT.byteSize()).isGreaterThan(0);
    }

    /**
     * Story 316 review — every member offset of {@code PaHostApiInfo}, against
     * the vendored {@code lib/portaudio/include/portaudio.h}.
     *
     * <p>Asserted member by member rather than by {@code byteSize()} alone
     * because a struct can be the right total size and still be read wrong:
     * {@code getHostApiName} dereferences the {@code name} member as a
     * POINTER, so an offset that is off by four reads half a pointer and half
     * of {@code type}, then hands the result to {@code reinterpret} — a silent
     * native-memory misread, which is exactly why the neighbouring layouts
     * spell their padding out instead of trusting
     * {@link MemoryLayout#structLayout(MemoryLayout...)} to insert it.</p>
     *
     * <p>Pure Java: it reads a constant and touches no native memory, so it
     * says the same thing on every host, installed PortAudio or not — which
     * matters because no CI workflow in this repository installs one.</p>
     */
    @Test
    void shouldDefineHostApiInfoLayoutMemberByMember() {
        MemoryLayout layout = PortAudioBindings.PA_HOST_API_INFO_LAYOUT;
        assertThat(layout).isNotNull();
        assertThat(offsetOf(layout, "structVersion")).as("structVersion").isZero();
        assertThat(offsetOf(layout, "type"))
                .as("PaHostApiTypeId is a plain C enum, passed as int")
                .isEqualTo(4L);
        assertThat(offsetOf(layout, "name"))
                .as("two ints precede the const char*, so on a 64-bit data model it "
                        + "lands on 8 with no padding before it")
                .isEqualTo(8L);
        assertThat(offsetOf(layout, "deviceCount")).as("deviceCount").isEqualTo(16L);
        assertThat(offsetOf(layout, "defaultInputDevice"))
                .as("PaDeviceIndex is a typedef of int")
                .isEqualTo(20L);
        assertThat(offsetOf(layout, "defaultOutputDevice"))
                .as("PaDeviceIndex is a typedef of int")
                .isEqualTo(24L);
        assertThat(layout.byteSize())
                .as("three trailing ints end the struct on 28, and a C compiler rounds it "
                        + "up to a multiple of the pointer alignment — four explicit bytes "
                        + "of padding, because structLayout adds none of its own")
                .isEqualTo(32L);
    }

    /**
     * {@code Pa_GetHostApiInfo} is the one symbol bound tolerantly, so
     * {@code getHostApiName} must answer without throwing whether or not the
     * loaded library exports it — and whether or not a library was loaded at
     * all.
     *
     * <p>Whether the library is present is a property of the host, not of this
     * code, so a test that demanded either answer would be a test that fails
     * somewhere. What IS asserted is the contract every caller depends on — a
     * name or {@code null}, never an exception — for a host API index that no
     * PortAudio build will describe, which is the degraded path on a host that
     * HAS the library as much as on one that does not.</p>
     */
    @Test
    void hostApiNameShouldDegradeToNullRatherThanThrowOnAnyHost() {
        PortAudioBindings bindings = new PortAudioBindings();

        assertThat(bindings.getHostApiName(Integer.MAX_VALUE))
                .as("Pa_GetHostApiInfo answers NULL for an out-of-range index, and an "
                        + "unbound symbol degrades to the same NULL, so both paths land "
                        + "on null rather than on an exception")
                .isNull();
    }

    /**
     * Story 316 re-review — {@code PaStreamParameters} must describe the same
     * struct on both data models.
     *
     * <p>Its {@code sampleFormat} member is a C {@code unsigned long}, so it
     * is 4 bytes under LLP64 and 8 under LP64, and the padding a C compiler
     * inserts after it differs accordingly. The OFFSETS, however, do not: a
     * layout whose members land anywhere else is describing a struct PortAudio
     * is not reading. Asserting offsets rather than the member list is what
     * makes this test say the same thing on the Linux CI runner and on a
     * Windows workstation.</p>
     */
    @Test
    void shouldDefineStreamParametersLayoutIdenticallyOnBothDataModels() {
        MemoryLayout layout = PortAudioBindings.PA_STREAM_PARAMETERS_LAYOUT;
        assertThat(layout).isNotNull();
        assertThat(layout.byteSize())
                .as("PaStreamParameters is 32 bytes on every 64-bit platform this "
                        + "project targets, LLP64 padding included")
                .isEqualTo(32L);
        assertThat(offsetOf(layout, "device")).as("device").isZero();
        assertThat(offsetOf(layout, "channelCount")).as("channelCount").isEqualTo(4L);
        assertThat(offsetOf(layout, "sampleFormat")).as("sampleFormat").isEqualTo(8L);
        assertThat(offsetOf(layout, "suggestedLatency"))
                .as("suggestedLatency — a double, so it is 8-aligned, which is why a "
                        + "32-bit sampleFormat needs four explicit bytes of padding "
                        + "behind it rather than landing this member on offset 12")
                .isEqualTo(16L);
        assertThat(offsetOf(layout, "hostApiSpecificStreamInfo"))
                .as("hostApiSpecificStreamInfo")
                .isEqualTo(24L);
    }

    /**
     * The struct member that carries the ABI hazard: PortAudio's
     * {@code PaSampleFormat} is a typedef of {@code unsigned long}, so the
     * member layout has to be whatever the platform linker calls a C
     * {@code long} — never {@code JAVA_LONG} unconditionally.
     */
    @Test
    void streamParametersSampleFormatShouldBeTheCanonicalCLong() {
        MemoryLayout sampleFormat = PortAudioBindings.PA_STREAM_PARAMETERS_LAYOUT
                .select(MemoryLayout.PathElement.groupElement("sampleFormat"));
        assertThat(sampleFormat.withoutName())
                .as("PaSampleFormat is a C unsigned long, and this is the only member "
                        + "of the struct whose width changes between data models. The "
                        + "member name is dropped before comparing because the canonical "
                        + "layout the linker reports carries none, and a name is not part "
                        + "of the ABI")
                .isEqualTo(canonicalCLong());
    }

    /**
     * {@code Pa_OpenStream} takes {@code unsigned long framesPerBuffer} and a
     * {@code PaStreamFlags} that is another {@code unsigned long}, so both
     * must be declared as the platform's C {@code long}.
     *
     * <p>This one was latent rather than live: a {@code JAVA_LONG}
     * declaration under LLP64 describes a function PortAudio does not export,
     * and it has worked anyway only because the Win64 convention gives those
     * two arguments 8-byte stack slots whose upper halves the callee ignores.
     * The declaration is still wrong, it is still the same ABI mistake the
     * callback descriptor made, and it is the one the repo's own story 116
     * prescribes {@code canonicalLayouts().get("long")} for.</p>
     */
    @Test
    void openStreamDescriptorShouldUseTheCanonicalCLongForBothUnsignedLongs() {
        MemoryLayout canonical = canonicalCLong();
        assertThat(PortAudioBindings.PA_OPEN_STREAM_DESCRIPTOR.argumentLayouts().get(4))
                .as("framesPerBuffer is a C unsigned long")
                .isEqualTo(canonical);
        assertThat(PortAudioBindings.PA_OPEN_STREAM_DESCRIPTOR.argumentLayouts().get(5))
                .as("PaStreamFlags is a typedef of C unsigned long")
                .isEqualTo(canonical);
    }

    /**
     * The descriptor change above would break {@code openStream} on Windows
     * if nothing adapted the handle: {@code invokeExact} demands an exact type
     * match, and under LLP64 the raw downcall handle takes two {@code int}s
     * where {@code openStream} passes two {@code long}s.
     *
     * <p>Proven WITHOUT PortAudio installed — CI has none — by adapting a
     * stand-in handle of the descriptor's own type. The invocation at the end
     * is the load-bearing half: asserting only {@code type()} would still pass
     * if {@code asType} had been used, which refuses the {@code long}-to-
     * {@code int} narrowing LLP64 needs, so the call is made in exactly the
     * shape {@code openStream} makes it.</p>
     */
    @Test
    void adaptedOpenStreamHandleShouldTakeJavaLongsOnEveryPlatform() throws Throwable {
        MethodHandle standIn = MethodHandles.empty(
                PortAudioBindings.PA_OPEN_STREAM_DESCRIPTOR.toMethodType());

        MethodHandle adapted = PortAudioBindings.adaptOpenStreamHandle(standIn);

        assertThat(adapted.type())
                .as("callers talk about frames and flags, not about the width of the "
                        + "host's C long, so the Java signature must be the same on "
                        + "every platform")
                .isEqualTo(MethodType.methodType(int.class,
                        MemorySegment.class, MemorySegment.class, MemorySegment.class,
                        double.class, long.class, long.class,
                        MemorySegment.class, MemorySegment.class));

        int result = (int) adapted.invokeExact(
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                44100.0d, 512L, 0L, MemorySegment.NULL, MemorySegment.NULL);

        assertThat(result)
                .as("MethodHandles.empty returns the zero default; reaching this line at "
                        + "all is the assertion — a mismatched handle would have thrown "
                        + "WrongMethodTypeException from invokeExact")
                .isZero();
    }

    private static MemoryLayout canonicalCLong() {
        return Linker.nativeLinker().canonicalLayouts().get("long");
    }

    private static long offsetOf(MemoryLayout layout, String member) {
        return layout.byteOffset(MemoryLayout.PathElement.groupElement(member));
    }
}
