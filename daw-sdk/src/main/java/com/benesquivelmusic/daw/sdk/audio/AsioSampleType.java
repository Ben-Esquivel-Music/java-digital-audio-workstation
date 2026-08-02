package com.benesquivelmusic.daw.sdk.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;

/**
 * The Steinberg {@code ASIOSampleType} matrix and its conversion to and from
 * the engine's normalised float32 bus (story 312).
 *
 * <p>ASIO drivers do not all hand the host float32. A multi-channel USB
 * interface — this project's primary target — commonly exposes
 * {@code ASIOSTInt24LSB} or {@code ASIOSTInt32LSB}, and copying those bytes
 * verbatim into a float buffer reinterprets them as garbage: full-scale noise
 * on capture, and on playback the driver reads the host's float bytes as
 * integers, which is loud enough to damage speakers. This enum is the single
 * place that knows how to decode and encode each layout.</p>
 *
 * <h2>Conversion locus</h2>
 * <p>The conversion lives in Java rather than in the native shim's
 * {@code bufferSwitch} trampoline. The trampoline hands the JVM the driver's
 * raw buffer addresses and does no sample movement at all, as
 * {@code asioshim.cpp}, {@code asioshim.h} and {@code native/asio/README.md}
 * all record. The shim is also never compiled in a normal checkout — its
 * CMake target is skipped unless the non-redistributable Steinberg SDK is
 * supplied — so conversion code placed there would carry no test coverage,
 * whereas everything here is exercised by {@code AsioSampleTypeTest}.</p>
 *
 * <h2>Normalisation</h2>
 * <p>Decoding an integer format divides by {@code 2^(significantBits - 1)};
 * encoding clamps the float to {@code [-1, 1]} <em>first</em> and then scales
 * by {@code 2^(significantBits - 1) - 1}. Clamping before the scale is what
 * makes saturation structural: no finite float can reach a product outside
 * the container's range, so wraparound is unreachable. The clamp applied to
 * the rounded integer afterwards is belt-and-braces only. This matches the
 * convention already used by {@code WavFileReader}, {@code AiffFileReader}
 * and {@code WavExporter}.</p>
 *
 * <p>A {@code NaN} sample survives the float clamp as {@code NaN} and then
 * {@code Math.round} maps it to {@code 0}, so a {@code NaN} on the bus is
 * encoded as digital silence for that sample. That is deliberate: branching
 * on {@code NaN} would cost a comparison per sample on the driver's real-time
 * thread, and silence is the only safe interpretation at a hardware boundary.
 * The float formats are <em>not</em> clamped — the engine's float bus may
 * legitimately exceed &plusmn;1.0, and {@code FLOAT32_LSB} must round-trip
 * bit-exactly.</p>
 *
 * <h2>Right-justified {@code Int32} variants</h2>
 * <p>{@code ASIOSTInt32LSB16/18/20/24} (and their MSB twins) carry an
 * <em>N</em>-bit sample in the low <em>N</em> bits of a 32-bit container.
 * PortAudio's {@code pa_asio.cpp} confirms the reading: its ASIO&rarr;host
 * converter left-shifts by {@code 32 - N} (16, 14, 12, 8 respectively) to
 * fill a 32-bit container. The unused high bits are never trusted here —
 * {@code raw << shift >> shift} re-derives the sign locally rather than
 * assuming the driver sign-extended.</p>
 *
 * <h2>Real-time discipline</h2>
 * <p>{@link #decode} and {@link #encode} run on the driver's
 * {@code bufferSwitch} thread. They allocate nothing, lock nothing, log
 * nothing and box nothing. The format is resolved once per channel at
 * {@code ASIOCreateBuffers} time via {@link #forCode(int)}, and each call
 * performs at most one dispatch — on an {@code int} discriminant, so the
 * hot path is a {@code tableswitch} with neither an {@code invokedynamic}
 * bootstrap nor a synthetic {@code $SwitchMap} holder to initialise on the
 * real-time thread.</p>
 *
 * <p>Every {@link MemorySegment} access uses a {@code JAVA_*_UNALIGNED}
 * layout (or {@code JAVA_BYTE}). Driver buffers reach the JVM through
 * {@code MemorySegment.ofAddress(...).reinterpret(...)} and carry no
 * alignment guarantee at all; an aligned layout would throw
 * {@link IllegalArgumentException} on a misaligned driver buffer.</p>
 *
 * <p>DSD ({@code ASIOSTDSDInt8LSB1} / {@code MSB1} / {@code NER8}) is out of
 * scope by the story's Non-Goals and is reported as unsupported rather than
 * guessed at, as is any code the driver reports that is not in this table —
 * including the shim's {@code -1} "the driver refused to say".</p>
 */
enum AsioSampleType {

    /** {@code ASIOSTInt16MSB} — 16-bit signed, big-endian. */
    INT16_MSB(0, AsioSampleType.ENC_INT16, 2, 16, false),
    /** {@code ASIOSTInt24MSB} — 24-bit signed, packed 3 bytes, big-endian. */
    INT24_MSB(1, AsioSampleType.ENC_INT24, 3, 24, false),
    /** {@code ASIOSTInt32MSB} — 32-bit signed, big-endian. */
    INT32_MSB(2, AsioSampleType.ENC_INT32, 4, 32, false),
    /** {@code ASIOSTFloat32MSB} — IEEE&nbsp;754 binary32, big-endian. */
    FLOAT32_MSB(3, AsioSampleType.ENC_FLOAT32, 4, 0, false),
    /** {@code ASIOSTFloat64MSB} — IEEE&nbsp;754 binary64, big-endian. */
    FLOAT64_MSB(4, AsioSampleType.ENC_FLOAT64, 8, 0, false),
    /** {@code ASIOSTInt32MSB16} — 16 significant bits, right-justified in 32, big-endian. */
    INT32_MSB16(8, AsioSampleType.ENC_INT32, 4, 16, false),
    /** {@code ASIOSTInt32MSB18} — 18 significant bits, right-justified in 32, big-endian. */
    INT32_MSB18(9, AsioSampleType.ENC_INT32, 4, 18, false),
    /** {@code ASIOSTInt32MSB20} — 20 significant bits, right-justified in 32, big-endian. */
    INT32_MSB20(10, AsioSampleType.ENC_INT32, 4, 20, false),
    /** {@code ASIOSTInt32MSB24} — 24 significant bits, right-justified in 32, big-endian. */
    INT32_MSB24(11, AsioSampleType.ENC_INT32, 4, 24, false),
    /** {@code ASIOSTInt16LSB} — 16-bit signed, little-endian. */
    INT16_LSB(16, AsioSampleType.ENC_INT16, 2, 16, true),
    /** {@code ASIOSTInt24LSB} — 24-bit signed, packed 3 bytes, little-endian. */
    INT24_LSB(17, AsioSampleType.ENC_INT24, 3, 24, true),
    /** {@code ASIOSTInt32LSB} — 32-bit signed, little-endian. */
    INT32_LSB(18, AsioSampleType.ENC_INT32, 4, 32, true),
    /** {@code ASIOSTFloat32LSB} — IEEE&nbsp;754 binary32, little-endian; the x86 fast path. */
    FLOAT32_LSB(19, AsioSampleType.ENC_FLOAT32, 4, 0, true),
    /** {@code ASIOSTFloat64LSB} — IEEE&nbsp;754 binary64, little-endian. */
    FLOAT64_LSB(20, AsioSampleType.ENC_FLOAT64, 8, 0, true),
    /** {@code ASIOSTInt32LSB16} — 16 significant bits, right-justified in 32, little-endian. */
    INT32_LSB16(24, AsioSampleType.ENC_INT32, 4, 16, true),
    /** {@code ASIOSTInt32LSB18} — 18 significant bits, right-justified in 32, little-endian. */
    INT32_LSB18(25, AsioSampleType.ENC_INT32, 4, 18, true),
    /** {@code ASIOSTInt32LSB20} — 20 significant bits, right-justified in 32, little-endian. */
    INT32_LSB20(26, AsioSampleType.ENC_INT32, 4, 20, true),
    /** {@code ASIOSTInt32LSB24} — 24 significant bits, right-justified in 32, little-endian. */
    INT32_LSB24(27, AsioSampleType.ENC_INT32, 4, 24, true);

    // ------------------------------------------------------------------
    // Hot-path dispatch discriminants
    // ------------------------------------------------------------------
    //
    // Plain compile-time int constants rather than a nested enum: javac
    // inlines them into both the constant table above and the switch below,
    // so the dispatch is a bare tableswitch. A switch over a nested enum
    // would instead read a synthetic $SwitchMap array whose holder class is
    // initialised — under the JVM's class-init lock — on whichever thread
    // executes the switch first. That thread is the driver's real-time
    // callback thread, which must never take a lock.

    /** 2-byte signed container, all 16 bits significant. */
    private static final int ENC_INT16 = 0;
    /** 3-byte packed signed container, all 24 bits significant. */
    private static final int ENC_INT24 = 1;
    /** 4-byte signed container; {@code significantBits} may be 16/18/20/24/32. */
    private static final int ENC_INT32 = 2;
    /** 4-byte IEEE 754 binary32 container. */
    private static final int ENC_FLOAT32 = 3;
    /** 8-byte IEEE 754 binary64 container. */
    private static final int ENC_FLOAT64 = 4;

    /** Bytes in a packed 24-bit sample; not a {@code ValueLayout} size. */
    private static final int INT24_BYTES = 3;

    private final int code;
    private final int encoding;
    private final int bytesPerSample;
    private final int significantBits;

    /** The format's own byte order, used directly by the packed 24-bit paths. */
    private final boolean littleEndian;
    /** Whether the format's order differs from the host's, so bytes need swapping. */
    private final boolean swap;

    /** {@code 1 / 2^(significantBits - 1)}; unused (1.0) for the float formats. */
    private final float decodeScale;
    /** {@code 2^(significantBits - 1) - 1}; unused (1.0) for the float formats. */
    private final double encodeScale;
    /** {@code 2^(significantBits - 1) - 1}; the saturation limit. */
    private final int peak;

    AsioSampleType(int code, int encoding, int bytesPerSample,
                   int significantBits, boolean littleEndian) {
        this.code = code;
        this.encoding = encoding;
        this.bytesPerSample = bytesPerSample;
        this.significantBits = significantBits;
        this.littleEndian = littleEndian;
        // ByteOrder.nativeOrder() is called rather than read from a static
        // field: enum constants are constructed before any other static
        // field of the enum is assigned, so such a field would still be
        // false here.
        this.swap = littleEndian
                != (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN);
        boolean integer = encoding != ENC_FLOAT32 && encoding != ENC_FLOAT64;
        // 1L, not 1: 1 << 31 overflows int and yields Integer.MIN_VALUE.
        long fullScale = integer ? 1L << (significantBits - 1) : 1L;
        this.decodeScale = integer ? 1f / fullScale : 1f;
        this.encodeScale = integer ? fullScale - 1L : 1.0;
        this.peak = (int) (fullScale - 1L);
    }

    // ------------------------------------------------------------------
    // Resolution (control thread only)
    // ------------------------------------------------------------------

    /**
     * Resolves a driver-reported {@code ASIOSampleType} code.
     *
     * <p>Called once per channel at {@code ASIOCreateBuffers} time, never
     * from the real-time thread — the {@link Optional} allocation is
     * deliberate and safe there. A plain {@code switch} rather than a scan
     * over {@link #values()}, which would clone the constant array on every
     * call.</p>
     *
     * @param code the raw {@code ASIOSampleType} the driver reported, or
     *             {@code -1} when the shim could not obtain one
     * @return the matching format, or {@link Optional#empty()} for a DSD
     *         type, an unassigned code, or {@code -1}
     */
    static Optional<AsioSampleType> forCode(int code) {
        return switch (code) {
            case 0 -> Optional.of(INT16_MSB);
            case 1 -> Optional.of(INT24_MSB);
            case 2 -> Optional.of(INT32_MSB);
            case 3 -> Optional.of(FLOAT32_MSB);
            case 4 -> Optional.of(FLOAT64_MSB);
            case 8 -> Optional.of(INT32_MSB16);
            case 9 -> Optional.of(INT32_MSB18);
            case 10 -> Optional.of(INT32_MSB20);
            case 11 -> Optional.of(INT32_MSB24);
            case 16 -> Optional.of(INT16_LSB);
            case 17 -> Optional.of(INT24_LSB);
            case 18 -> Optional.of(INT32_LSB);
            case 19 -> Optional.of(FLOAT32_LSB);
            case 20 -> Optional.of(FLOAT64_LSB);
            case 24 -> Optional.of(INT32_LSB16);
            case 25 -> Optional.of(INT32_LSB18);
            case 26 -> Optional.of(INT32_LSB20);
            case 27 -> Optional.of(INT32_LSB24);
            // 32 / 33 / 40 are the DSD types, explicitly out of scope; every
            // other code is unassigned in the SDK, and -1 means the driver
            // refused to report one.
            default -> Optional.empty();
        };
    }

    /** Returns the {@code ASIOSampleType} enum value this format is declared as. */
    int code() {
        return code;
    }

    /**
     * Returns how many bytes one sample of one channel occupies in the
     * driver's buffer — 2, 3 (packed 24-bit), 4 or 8.
     *
     * <p>This is what sizes the {@link MemorySegment} view over a driver
     * buffer. Assuming 4 for every format under-sizes an
     * {@code ASIOSTFloat64*} view by half and over-sizes an
     * {@code ASIOSTInt16*} one.</p>
     *
     * @return the driver-side container size in bytes
     */
    int bytesPerSample() {
        return bytesPerSample;
    }

    /**
     * Returns how many bits of the container carry the sample, or {@code 0}
     * for the IEEE 754 float formats.
     */
    int significantBits() {
        return significantBits;
    }

    // ------------------------------------------------------------------
    // Decode (driver -> engine)
    // ------------------------------------------------------------------

    /**
     * Decodes {@code frames} driver samples into normalised float32.
     *
     * <p>Integer formats land in {@code [-1, 1]}; the float formats are
     * passed through unscaled, so a driver that hands back an out-of-range
     * float keeps it. Runs on the driver's real-time callback thread.</p>
     *
     * @param source      the driver's buffer for one channel; must hold at
     *                    least {@code frames * bytesPerSample()} bytes and
     *                    must not be null
     * @param destination receives the decoded samples in {@code [0, frames)};
     *                    must not be null
     * @param frames      how many samples to decode
     */
    @RealTimeSafe
    void decode(MemorySegment source, float[] destination, int frames) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        switch (encoding) {
            case ENC_FLOAT32 -> decodeFloat32(source, destination, frames);
            case ENC_FLOAT64 -> decodeFloat64(source, destination, frames);
            case ENC_INT16 -> decodeInt16(source, destination, frames);
            case ENC_INT24 -> decodeInt24(source, destination, frames);
            default -> decodeInt32(source, destination, frames);
        }
    }

    /**
     * Bit-exact passthrough. On the only supported target (x64 Windows)
     * {@code FLOAT32_LSB} needs no swap, so this stays the single bulk
     * {@link MemorySegment#copy} story 311 established.
     */
    @RealTimeSafe
    private void decodeFloat32(MemorySegment source, float[] destination, int frames) {
        if (!swap) {
            MemorySegment.copy(source, ValueLayout.JAVA_FLOAT_UNALIGNED, 0L,
                    destination, 0, frames);
            return;
        }
        for (int frame = 0; frame < frames; frame++) {
            int raw = source.get(ValueLayout.JAVA_INT_UNALIGNED,
                    (long) frame * Integer.BYTES);
            destination[frame] = Float.intBitsToFloat(Integer.reverseBytes(raw));
        }
    }

    @RealTimeSafe
    private void decodeFloat64(MemorySegment source, float[] destination, int frames) {
        boolean reverse = swap;
        for (int frame = 0; frame < frames; frame++) {
            long raw = source.get(ValueLayout.JAVA_LONG_UNALIGNED,
                    (long) frame * Long.BYTES);
            if (reverse) {
                raw = Long.reverseBytes(raw);
            }
            destination[frame] = (float) Double.longBitsToDouble(raw);
        }
    }

    @RealTimeSafe
    private void decodeInt16(MemorySegment source, float[] destination, int frames) {
        boolean reverse = swap;
        float scale = decodeScale;
        for (int frame = 0; frame < frames; frame++) {
            short raw = source.get(ValueLayout.JAVA_SHORT_UNALIGNED,
                    (long) frame * Short.BYTES);
            if (reverse) {
                raw = Short.reverseBytes(raw);
            }
            destination[frame] = raw * scale;
        }
    }

    /**
     * Packed 24-bit: exactly three bytes per sample, assembled into the top
     * 24 bits of an {@code int} and then arithmetically shifted down, which
     * sign-extends. Same construction as {@code WavFileReader} (little-endian)
     * and {@code AiffFileReader} (big-endian).
     */
    @RealTimeSafe
    private void decodeInt24(MemorySegment source, float[] destination, int frames) {
        boolean little = littleEndian;
        float scale = decodeScale;
        for (int frame = 0; frame < frames; frame++) {
            long offset = (long) frame * INT24_BYTES;
            int b0 = source.get(ValueLayout.JAVA_BYTE, offset) & 0xFF;
            int b1 = source.get(ValueLayout.JAVA_BYTE, offset + 1) & 0xFF;
            int b2 = source.get(ValueLayout.JAVA_BYTE, offset + 2) & 0xFF;
            int value = little
                    ? ((b2 << 24) | (b1 << 16) | (b0 << 8)) >> 8
                    : ((b0 << 24) | (b1 << 16) | (b2 << 8)) >> 8;
            destination[frame] = value * scale;
        }
    }

    /**
     * Covers the full 32-bit formats and the right-justified 16/18/20/24
     * variants alike: {@code shift} is {@code 32 - significantBits}, which is
     * zero — and therefore a no-op — for the full-width types.
     */
    @RealTimeSafe
    private void decodeInt32(MemorySegment source, float[] destination, int frames) {
        boolean reverse = swap;
        float scale = decodeScale;
        int shift = Integer.SIZE - significantBits;
        for (int frame = 0; frame < frames; frame++) {
            int raw = source.get(ValueLayout.JAVA_INT_UNALIGNED,
                    (long) frame * Integer.BYTES);
            if (reverse) {
                raw = Integer.reverseBytes(raw);
            }
            // The driver is not trusted to have sign-extended the unused
            // high bits of a right-justified sample; this re-derives the
            // sign from bit (significantBits - 1) locally.
            destination[frame] = (raw << shift >> shift) * scale;
        }
    }

    // ------------------------------------------------------------------
    // Encode (engine -> driver)
    // ------------------------------------------------------------------

    /**
     * Encodes {@code frames} normalised float32 samples into the driver's
     * layout, saturating at full scale for the integer formats.
     *
     * <p>Runs on the driver's real-time callback thread.</p>
     *
     * @param source      the engine's samples for one channel, read from
     *                    {@code [0, frames)}; must not be null
     * @param destination the driver's buffer for one channel; must hold at
     *                    least {@code frames * bytesPerSample()} bytes and
     *                    must not be null
     * @param frames      how many samples to encode
     */
    @RealTimeSafe
    void encode(float[] source, MemorySegment destination, int frames) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        switch (encoding) {
            case ENC_FLOAT32 -> encodeFloat32(source, destination, frames);
            case ENC_FLOAT64 -> encodeFloat64(source, destination, frames);
            case ENC_INT16 -> encodeInt16(source, destination, frames);
            case ENC_INT24 -> encodeInt24(source, destination, frames);
            default -> encodeInt32(source, destination, frames);
        }
    }

    /** Bit-exact passthrough; the no-swap case is story 311's bulk copy. */
    @RealTimeSafe
    private void encodeFloat32(float[] source, MemorySegment destination, int frames) {
        if (!swap) {
            MemorySegment.copy(source, 0, destination,
                    ValueLayout.JAVA_FLOAT_UNALIGNED, 0L, frames);
            return;
        }
        for (int frame = 0; frame < frames; frame++) {
            int bits = Float.floatToRawIntBits(source[frame]);
            destination.set(ValueLayout.JAVA_INT_UNALIGNED,
                    (long) frame * Integer.BYTES, Integer.reverseBytes(bits));
        }
    }

    @RealTimeSafe
    private void encodeFloat64(float[] source, MemorySegment destination, int frames) {
        boolean reverse = swap;
        for (int frame = 0; frame < frames; frame++) {
            long bits = Double.doubleToRawLongBits(source[frame]);
            if (reverse) {
                bits = Long.reverseBytes(bits);
            }
            destination.set(ValueLayout.JAVA_LONG_UNALIGNED,
                    (long) frame * Long.BYTES, bits);
        }
    }

    @RealTimeSafe
    private void encodeInt16(float[] source, MemorySegment destination, int frames) {
        boolean reverse = swap;
        double scale = encodeScale;
        int limit = peak;
        for (int frame = 0; frame < frames; frame++) {
            short value = (short) quantize(source[frame], scale, limit);
            if (reverse) {
                value = Short.reverseBytes(value);
            }
            destination.set(ValueLayout.JAVA_SHORT_UNALIGNED,
                    (long) frame * Short.BYTES, value);
        }
    }

    /**
     * Packed 24-bit: exactly three bytes written per sample, so a
     * {@code frames * 3}-byte driver buffer is filled precisely and no
     * fourth byte is touched. Same byte order as {@code WavExporter}
     * (little-endian) reversed for the MSB variant.
     */
    @RealTimeSafe
    private void encodeInt24(float[] source, MemorySegment destination, int frames) {
        boolean little = littleEndian;
        double scale = encodeScale;
        int limit = peak;
        for (int frame = 0; frame < frames; frame++) {
            int value = quantize(source[frame], scale, limit);
            long offset = (long) frame * INT24_BYTES;
            if (little) {
                destination.set(ValueLayout.JAVA_BYTE, offset, (byte) value);
                destination.set(ValueLayout.JAVA_BYTE, offset + 1, (byte) (value >> 8));
                destination.set(ValueLayout.JAVA_BYTE, offset + 2, (byte) (value >> 16));
            } else {
                destination.set(ValueLayout.JAVA_BYTE, offset, (byte) (value >> 16));
                destination.set(ValueLayout.JAVA_BYTE, offset + 1, (byte) (value >> 8));
                destination.set(ValueLayout.JAVA_BYTE, offset + 2, (byte) value);
            }
        }
    }

    /**
     * Covers the full 32-bit formats and the right-justified variants alike.
     * {@link #quantize} already produces a signed value inside
     * {@code [-peak, peak]}, which for a right-justified format occupies the
     * low {@code significantBits} bits with the sign replicated above — the
     * same bit pattern PortAudio's host&rarr;ASIO converter writes.
     */
    @RealTimeSafe
    private void encodeInt32(float[] source, MemorySegment destination, int frames) {
        boolean reverse = swap;
        double scale = encodeScale;
        int limit = peak;
        for (int frame = 0; frame < frames; frame++) {
            int value = quantize(source[frame], scale, limit);
            if (reverse) {
                value = Integer.reverseBytes(value);
            }
            destination.set(ValueLayout.JAVA_INT_UNALIGNED,
                    (long) frame * Integer.BYTES, value);
        }
    }

    /**
     * Clamps a bus sample to {@code [-1, 1]} and scales it to the format's
     * integer range.
     *
     * <p>Static, and given the scale and the limit as arguments rather than
     * reading them from the enum's fields, so the caller can hoist both out
     * of its loop and the JIT sees a trivially inlinable leaf.</p>
     *
     * <p>The clamp on the rounded result is redundant — the float was already
     * clamped to {@code [-1, 1]} before the multiply, so the product cannot
     * leave {@code [-limit, limit]} — and is kept as a belt-and-braces guard
     * against a future scale-factor change reintroducing wraparound at the
     * hardware boundary. {@code NaN} survives the float clamp and is mapped
     * to {@code 0} by {@link Math#round(double)}: silence, not noise.</p>
     */
    @RealTimeSafe
    private static int quantize(float sample, double scale, int limit) {
        float clamped = Math.clamp(sample, -1f, 1f);
        return Math.clamp(Math.round(clamped * scale), -limit, limit);
    }
}
