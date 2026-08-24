package com.benesquivelmusic.daw.core.audio;

import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;

/**
 * The one place this codebase resolves how wide the platform's C
 * {@code long} is.
 *
 * <p>C {@code long} is the only integer type whose width changes between the
 * two 64-bit data models this DAW ships on: it is 64 bits under LP64
 * (Linux, macOS) and 32 bits under LLP64 (Windows), while {@code int} and
 * pointers are the same width on both. Every FFM binding that names a C
 * {@code long} — directly, or through a typedef such as PortAudio's
 * {@code PaSampleFormat} and {@code PaStreamCallbackFlags} — therefore has to
 * ask the platform linker for the answer instead of writing
 * {@link ValueLayout#JAVA_LONG} and hoping.</p>
 *
 * <p>Getting it wrong in a DOWNCALL descriptor mis-marshals an argument. In an
 * UPCALL descriptor it is worse: the callback reads a full 64-bit argument
 * slot where the host wrote only 32 bits, and nothing in the ABI obliges the
 * caller to have zeroed the rest. A frame count read that way can size a
 * {@link java.lang.foreign.MemorySegment#reinterpret(long) reinterpret} to a
 * fictitious extent and let a fill run far past the driver's real buffer.
 * That is native memory corruption produced by a declaration, not by a bug in
 * the loop.</p>
 *
 * <p>A per-class {@code static final ValueLayout C_LONG = resolve...()} copy
 * is the wrong shape for a fact of the running platform. It is one fact, and
 * duplicating it means each new binding class re-derives it — so the ones that
 * forget (and the ones that were written before anyone noticed) diverge
 * silently, with nothing in the build able to tell that two classes disagree
 * about the same ABI. Callers hold the layout, they do not each own it. This
 * class replaced exactly such a pair of copies in
 * {@code OggVorbisExporter} and {@code OggVorbisFileReader}.</p>
 */
public final class NativeAbi {

    /**
     * The platform's canonical C {@code long} layout: 8 bytes under LP64,
     * 4 bytes under LLP64.
     *
     * <p>Read from {@link Linker#canonicalLayouts()} rather than chosen from
     * {@code os.name}, so it is the same answer the linker itself will use
     * when it marshals a descriptor built out of this layout.</p>
     */
    public static final ValueLayout C_LONG = resolveCLongLayout();

    /**
     * Whether the platform's C {@code long} is 32 bits wide — that is,
     * whether this is an LLP64 host such as Windows.
     *
     * <p>Exposed as a boolean because the interesting call sites are not
     * asking for a width, they are choosing between two code shapes: which
     * accessor writes a struct member, whether a struct needs explicit
     * padding, which Java entry point an upcall stub must bind. Deriving it
     * once here keeps those sites from re-deriving {@code byteSize() == 4}
     * and disagreeing about the edge case.</p>
     */
    public static final boolean C_LONG_IS_32_BIT = C_LONG.byteSize() == Integer.BYTES;

    private NativeAbi() {
    }

    private static ValueLayout resolveCLongLayout() {
        MemoryLayout longLayout = Linker.nativeLinker().canonicalLayouts().get("long");
        if (longLayout == null) {
            throw new UnsupportedOperationException(
                    "Native C ABI layout for 'long' is not available from the platform linker");
        }
        if (!(longLayout instanceof ValueLayout valueLayout)) {
            throw new UnsupportedOperationException(
                    "Native C ABI layout for 'long' is not a ValueLayout: "
                            + longLayout.getClass().getName());
        }
        return valueLayout;
    }
}
