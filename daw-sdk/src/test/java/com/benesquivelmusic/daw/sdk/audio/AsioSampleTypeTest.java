package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 312 acceptance tests for {@link AsioSampleType}: the full Steinberg
 * {@code ASIOSampleType} matrix converted to and from the engine's normalised
 * float32 bus.
 *
 * <p>The driver-side bytes are read back with a <em>test-local</em> decoder
 * ({@link #storedInteger}) that assembles the integer from raw bytes and picks
 * its byte order from the ASIO constant's own name, so a bug in the production
 * decoder cannot cancel out the matching bug in the production encoder. Every
 * segment is allocated from a test-owned {@link Arena}, exactly as
 * {@code AsioBufferSwitchShimTest} stands in for a driver's buffers.</p>
 *
 * <p>The constant table itself was confirmed against two independent copies of
 * Steinberg's {@code asio.h} (the ASIO SDK bundled in {@code thestk/rtaudio}
 * and in {@code kxproject/kx-audio-driver}), and the right-justified reading of
 * the {@code Int32×16/18/20/24} variants against PortAudio's
 * {@code pa_asio.cpp}, whose ASIO&rarr;host converter left-shifts by
 * {@code 32 - N}.</p>
 */
class AsioSampleTypeTest {

    /** Sentinel written past the end of a packed 24-bit buffer. */
    private static final byte TAIL = (byte) 0xA5;

    private Arena arena;

    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
    }

    @AfterEach
    void tearDown() {
        arena.close();
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    @Test
    void forCodeResolvesEveryConstantInTheSteinbergAsioSampleTypeTable() {
        assertThat(AsioSampleType.forCode(0)).contains(AsioSampleType.INT16_MSB);
        assertThat(AsioSampleType.forCode(1)).contains(AsioSampleType.INT24_MSB);
        assertThat(AsioSampleType.forCode(2)).contains(AsioSampleType.INT32_MSB);
        assertThat(AsioSampleType.forCode(3)).contains(AsioSampleType.FLOAT32_MSB);
        assertThat(AsioSampleType.forCode(4)).contains(AsioSampleType.FLOAT64_MSB);
        assertThat(AsioSampleType.forCode(8)).contains(AsioSampleType.INT32_MSB16);
        assertThat(AsioSampleType.forCode(9)).contains(AsioSampleType.INT32_MSB18);
        assertThat(AsioSampleType.forCode(10)).contains(AsioSampleType.INT32_MSB20);
        assertThat(AsioSampleType.forCode(11)).contains(AsioSampleType.INT32_MSB24);
        assertThat(AsioSampleType.forCode(16)).contains(AsioSampleType.INT16_LSB);
        assertThat(AsioSampleType.forCode(17)).contains(AsioSampleType.INT24_LSB);
        assertThat(AsioSampleType.forCode(18)).contains(AsioSampleType.INT32_LSB);
        assertThat(AsioSampleType.forCode(19)).contains(AsioSampleType.FLOAT32_LSB);
        assertThat(AsioSampleType.forCode(20)).contains(AsioSampleType.FLOAT64_LSB);
        assertThat(AsioSampleType.forCode(24)).contains(AsioSampleType.INT32_LSB16);
        assertThat(AsioSampleType.forCode(25)).contains(AsioSampleType.INT32_LSB18);
        assertThat(AsioSampleType.forCode(26)).contains(AsioSampleType.INT32_LSB20);
        assertThat(AsioSampleType.forCode(27)).contains(AsioSampleType.INT32_LSB24);
    }

    @Test
    void everyConstantResolvesBackFromItsOwnDeclaredCode() {
        for (AsioSampleType type : AsioSampleType.values()) {
            assertThat(AsioSampleType.forCode(type.code()))
                    .as("%s declares code %d", type, type.code())
                    .contains(type);
        }
    }

    /**
     * DSD (32 / 33 / 40) is out of scope by the story's Non-Goals, {@code -1}
     * is the native shim's "the driver refused to report a type", and the gap
     * codes are unassigned in the SDK. None of them may be guessed at.
     */
    @Test
    void forCodeRejectsDsdUnassignedAndUnknownCodes() {
        int[] rejected = {-1, 5, 6, 7, 12, 13, 14, 15, 21, 22, 23, 28, 29,
                32, 33, 40, 41, Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int code : rejected) {
            assertThat(AsioSampleType.forCode(code))
                    .as("ASIOSampleType %d must not resolve to a converter", code)
                    .isEmpty();
        }
    }

    /**
     * The byte size that sizes the {@link MemorySegment} view over a driver
     * buffer. Assuming 4 for every format under-sizes a {@code Float64} view by
     * half — the failure mode story 311's fixed {@code frames * 4} view had.
     */
    @Test
    void bytesPerSampleMatchesEachDriverSideContainer() {
        assertThat(AsioSampleType.INT16_MSB.bytesPerSample()).isEqualTo(2);
        assertThat(AsioSampleType.INT16_LSB.bytesPerSample()).isEqualTo(2);
        assertThat(AsioSampleType.INT24_MSB.bytesPerSample())
                .as("packed 24-bit is exactly three bytes, not four")
                .isEqualTo(3);
        assertThat(AsioSampleType.INT24_LSB.bytesPerSample()).isEqualTo(3);
        assertThat(AsioSampleType.INT32_MSB.bytesPerSample()).isEqualTo(4);
        assertThat(AsioSampleType.INT32_LSB.bytesPerSample()).isEqualTo(4);
        assertThat(AsioSampleType.FLOAT32_MSB.bytesPerSample()).isEqualTo(4);
        assertThat(AsioSampleType.FLOAT32_LSB.bytesPerSample()).isEqualTo(4);
        assertThat(AsioSampleType.FLOAT64_MSB.bytesPerSample())
                .as("IEEE 754 binary64 is eight bytes")
                .isEqualTo(8);
        assertThat(AsioSampleType.FLOAT64_LSB.bytesPerSample()).isEqualTo(8);
        for (AsioSampleType type : rightJustifiedInt32Formats()) {
            assertThat(type.bytesPerSample())
                    .as("%s is carried in a 32-bit container", type)
                    .isEqualTo(4);
        }
    }

    // ------------------------------------------------------------------
    // Round trip
    // ------------------------------------------------------------------

    @Test
    void everyFormatRoundTripsThePatternWithinItsQuantisationTolerance() {
        float[] pattern = pattern();
        for (AsioSampleType type : AsioSampleType.values()) {
            MemorySegment segment = allocate(type, pattern.length);
            type.encode(pattern, segment, pattern.length);
            float[] decoded = new float[pattern.length];
            type.decode(segment, decoded, pattern.length);

            for (int i = 0; i < pattern.length; i++) {
                assertThat(decoded[i])
                        .as("%s round-trip of sample %d (%s)", type, i, pattern[i])
                        .isCloseTo(pattern[i], within(tolerance(type)));
            }
        }
    }

    /**
     * The story requires {@code Float32LSB} to be bit-exact, which is also what
     * keeps story 311's bulk-copy fast path honest. {@code Float64} is exact
     * for any float-representable input because {@code float -> double ->
     * float} loses nothing.
     */
    @Test
    void theFloatFormatsRoundTripBitExactlyRatherThanApproximately() {
        float[] pattern = pattern();
        AsioSampleType[] floatFormats = {
                AsioSampleType.FLOAT32_LSB, AsioSampleType.FLOAT32_MSB,
                AsioSampleType.FLOAT64_LSB, AsioSampleType.FLOAT64_MSB};
        for (AsioSampleType type : floatFormats) {
            MemorySegment segment = allocate(type, pattern.length);
            type.encode(pattern, segment, pattern.length);
            float[] decoded = new float[pattern.length];
            type.decode(segment, decoded, pattern.length);

            assertThat(decoded)
                    .as("%s must not quantise or rescale", type)
                    .containsExactly(pattern);
        }
    }

    /**
     * The engine's float bus may legitimately exceed &plusmn;1.0 (story 127's
     * 64-bit internal mix), and a driver that reports a float format expects
     * that value through unchanged — clamping it here would be a silent
     * limiter no one asked for.
     */
    @Test
    void theFloatFormatsPassOutOfRangeSamplesThroughUnclamped() {
        float[] hot = {4f, -4f, 1.5f};
        for (AsioSampleType type : new AsioSampleType[] {
                AsioSampleType.FLOAT32_LSB, AsioSampleType.FLOAT64_LSB}) {
            MemorySegment segment = allocate(type, hot.length);
            type.encode(hot, segment, hot.length);
            float[] decoded = new float[hot.length];
            type.decode(segment, decoded, hot.length);

            assertThat(decoded).as("%s must not clamp", type).containsExactly(hot);
        }
    }

    // ------------------------------------------------------------------
    // Saturation
    // ------------------------------------------------------------------

    /**
     * The raw stored integer is asserted, not the re-decoded float: a
     * wraparound to the opposite rail is the failure this guards against, and
     * decoding {@code -peak - 1} back yields a perfectly plausible
     * {@code -1.0f}.
     */
    @Test
    void integerFormatsSaturateAtFullScaleInsteadOfWrappingAround() {
        float[] tooLoud = {1.0001f, 2f, Float.MAX_VALUE, Float.POSITIVE_INFINITY};
        float[] tooQuiet = {-1.0001f, -2f, -Float.MAX_VALUE, Float.NEGATIVE_INFINITY};
        for (AsioSampleType type : integerFormats()) {
            int peak = peakOf(type);
            MemorySegment segment = allocate(type, tooLoud.length);

            type.encode(tooLoud, segment, tooLoud.length);
            for (int i = 0; i < tooLoud.length; i++) {
                assertThat(storedInteger(segment, type, i))
                        .as("%s must clamp %s to +%d", type, tooLoud[i], peak)
                        .isEqualTo(peak);
            }

            type.encode(tooQuiet, segment, tooQuiet.length);
            for (int i = 0; i < tooQuiet.length; i++) {
                assertThat(storedInteger(segment, type, i))
                        .as("%s must clamp %s to -%d", type, tooQuiet[i], peak)
                        .isEqualTo(-peak);
            }
        }
    }

    /**
     * {@code Math.clamp} propagates {@code NaN} and {@code Math.round(NaN)} is
     * {@code 0}, so a {@code NaN} on the bus becomes digital silence rather
     * than a full-scale rail. Documented behaviour, pinned here so a future
     * refactor cannot change it unnoticed.
     */
    @Test
    void aNotANumberSampleIsEncodedAsSilenceForEveryIntegerFormat() {
        float[] nan = {Float.NaN};
        for (AsioSampleType type : integerFormats()) {
            MemorySegment segment = allocate(type, 1);
            type.encode(nan, segment, 1);

            assertThat(storedInteger(segment, type, 0))
                    .as("%s must encode NaN as silence", type)
                    .isZero();
        }
    }

    // ------------------------------------------------------------------
    // Packed 24-bit
    // ------------------------------------------------------------------

    /**
     * {@code Int24LSB} must consume and produce exactly three bytes per
     * sample. A four-byte stride would silently double the buffer the driver
     * hears and leave every other sample stale.
     */
    @Test
    void int24LsbWritesExactlyThreeLittleEndianBytesPerSampleAndTouchesNoFourth() {
        // round(0.5 * 8388607) = 4194304 = 0x400000
        // round(-0.5 * 8388607) = -4194303 = 0xFFC00001
        MemorySegment segment = arena.allocate(2L * 3 + 1);
        segment.set(ValueLayout.JAVA_BYTE, 6, TAIL);

        AsioSampleType.INT24_LSB.encode(new float[] {0.5f, -0.5f}, segment, 2);

        assertThat(bytesOf(segment, 0, 3))
                .as("+0.5 packs little-endian as 00 00 40")
                .containsExactly((byte) 0x00, (byte) 0x00, (byte) 0x40);
        assertThat(bytesOf(segment, 3, 3))
                .as("-0.5 packs little-endian as 01 00 C0")
                .containsExactly((byte) 0x01, (byte) 0x00, (byte) 0xC0);
        assertThat(segment.get(ValueLayout.JAVA_BYTE, 6))
                .as("a three-byte format must not spill into a fourth byte")
                .isEqualTo(TAIL);
    }

    @Test
    void int24MsbWritesTheSameSampleInReverseByteOrder() {
        MemorySegment segment = arena.allocate(2L * 3);

        AsioSampleType.INT24_MSB.encode(new float[] {0.5f, -0.5f}, segment, 2);

        assertThat(bytesOf(segment, 0, 3))
                .containsExactly((byte) 0x40, (byte) 0x00, (byte) 0x00);
        assertThat(bytesOf(segment, 3, 3))
                .containsExactly((byte) 0xC0, (byte) 0x00, (byte) 0x01);
    }

    @Test
    void int24DecodeSignExtendsANegativeSampleRatherThanReadingItAsLarge() {
        MemorySegment little = arena.allocate(3L);
        // 00 00 80 little-endian is 0x800000 — the most negative 24-bit value.
        little.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x00);
        little.set(ValueLayout.JAVA_BYTE, 1, (byte) 0x00);
        little.set(ValueLayout.JAVA_BYTE, 2, (byte) 0x80);
        MemorySegment big = arena.allocate(3L);
        big.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x80);
        big.set(ValueLayout.JAVA_BYTE, 1, (byte) 0x00);
        big.set(ValueLayout.JAVA_BYTE, 2, (byte) 0x00);

        float[] decodedLittle = new float[1];
        AsioSampleType.INT24_LSB.decode(little, decodedLittle, 1);
        float[] decodedBig = new float[1];
        AsioSampleType.INT24_MSB.decode(big, decodedBig, 1);

        assertThat(decodedLittle[0])
                .as("0x800000 is -8388608, so it normalises to exactly -1.0")
                .isEqualTo(-1f);
        assertThat(decodedBig[0]).isEqualTo(-1f);
    }

    // ------------------------------------------------------------------
    // Endianness
    // ------------------------------------------------------------------

    /**
     * Every MSB constant must produce exactly the byte reversal of its LSB
     * twin for the same input — the property that makes the LSB variants the
     * no-swap fast path on x64 Windows, the only target ASIO runs on.
     */
    @Test
    void everyMsbFormatStoresTheByteReversalOfItsLsbTwin() {
        AsioSampleType[][] twins = {
                {AsioSampleType.INT16_MSB, AsioSampleType.INT16_LSB},
                {AsioSampleType.INT24_MSB, AsioSampleType.INT24_LSB},
                {AsioSampleType.INT32_MSB, AsioSampleType.INT32_LSB},
                {AsioSampleType.FLOAT32_MSB, AsioSampleType.FLOAT32_LSB},
                {AsioSampleType.FLOAT64_MSB, AsioSampleType.FLOAT64_LSB},
                {AsioSampleType.INT32_MSB16, AsioSampleType.INT32_LSB16},
                {AsioSampleType.INT32_MSB18, AsioSampleType.INT32_LSB18},
                {AsioSampleType.INT32_MSB20, AsioSampleType.INT32_LSB20},
                {AsioSampleType.INT32_MSB24, AsioSampleType.INT32_LSB24}};
        float[] pattern = pattern();

        for (AsioSampleType[] twin : twins) {
            AsioSampleType msb = twin[0];
            AsioSampleType lsb = twin[1];
            int width = lsb.bytesPerSample();
            MemorySegment big = allocate(msb, pattern.length);
            MemorySegment little = allocate(lsb, pattern.length);
            msb.encode(pattern, big, pattern.length);
            lsb.encode(pattern, little, pattern.length);

            for (int frame = 0; frame < pattern.length; frame++) {
                byte[] bigBytes = bytesOf(big, (long) frame * width, width);
                byte[] littleBytes = bytesOf(little, (long) frame * width, width);
                assertThat(bigBytes)
                        .as("%s frame %d must be %s byte-reversed", msb, frame, lsb)
                        .containsExactly(reversed(littleBytes));
            }
        }
    }

    // ------------------------------------------------------------------
    // Right-justified Int32 variants
    // ------------------------------------------------------------------

    /**
     * The N significant bits sit in the <em>low</em> N bits of the 32-bit
     * container, so full scale is {@code 32767} for {@code Int32LSB16} — not
     * {@code 0x7FFF0000}, which is what a left-justified reading would store.
     */
    @Test
    void rightJustifiedFormatsStoreFullScaleInTheLowBitsOfTheContainer() {
        MemorySegment segment = allocate(AsioSampleType.INT32_LSB16, 1);
        AsioSampleType.INT32_LSB16.encode(new float[] {1f}, segment, 1);
        assertThat(storedInteger(segment, AsioSampleType.INT32_LSB16, 0))
                .as("Int32LSB16 full scale is right-justified 32767")
                .isEqualTo(32767);

        MemorySegment wide = allocate(AsioSampleType.INT32_LSB24, 1);
        AsioSampleType.INT32_LSB24.encode(new float[] {-1f}, wide, 1);
        assertThat(storedInteger(wide, AsioSampleType.INT32_LSB24, 0))
                .as("Int32LSB24 negative full scale is right-justified -8388607")
                .isEqualTo(-8388607);
    }

    @Test
    void rightJustifiedFormatsNormaliseAgainstTheirOwnBitDepth() {
        MemorySegment segment = allocate(AsioSampleType.INT32_LSB16, 1);
        writeStoredInteger(segment, AsioSampleType.INT32_LSB16, 0, 1);
        float[] decoded = new float[1];

        AsioSampleType.INT32_LSB16.decode(segment, decoded, 1);

        assertThat(decoded[0])
                .as("one LSB of a 16-bit sample is 1/32768, not 1/2^31")
                .isEqualTo(1f / 32768f);
    }

    /**
     * A driver is not obliged to sign-extend the unused high bits of a
     * right-justified container. Reading {@code 0x0000FFFF} as a plain
     * {@code int} would give {@code 65535} and normalise to {@code +2.0}
     * — full-scale noise where the sample was one LSB below zero.
     */
    @Test
    void rightJustifiedDecodeSignExtendsInsteadOfTrustingTheDriversHighBits() {
        MemorySegment segment = allocate(AsioSampleType.INT32_LSB16, 1);
        writeStoredInteger(segment, AsioSampleType.INT32_LSB16, 0, 0x0000FFFF);
        float[] decoded = new float[1];

        AsioSampleType.INT32_LSB16.decode(segment, decoded, 1);

        assertThat(decoded[0])
                .as("the low 16 bits all set is -1, so it normalises to -1/32768")
                .isEqualTo(-1f / 32768f);
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    /** Full scale, negative full scale, zero, &plusmn;0.5 and one sine cycle. */
    private static float[] pattern() {
        float[] values = new float[13];
        values[0] = 1f;
        values[1] = -1f;
        values[2] = 0f;
        values[3] = 0.5f;
        values[4] = -0.5f;
        for (int i = 0; i < 8; i++) {
            values[5 + i] = (float) (0.8 * Math.sin(2 * Math.PI * i / 8));
        }
        return values;
    }

    /**
     * One quantisation step plus the half-LSB rounding error the asymmetric
     * {@code 2^(bits-1) - 1} encode scale introduces, rounded up to two steps,
     * plus two float ULPs for the wide formats whose step is finer than
     * float's own 24-bit mantissa. Zero for the float formats, which are
     * bit-exact.
     */
    private static float tolerance(AsioSampleType type) {
        int bits = type.significantBits();
        if (bits == 0) {
            return 0f;
        }
        return 2f / (float) (1L << (bits - 1)) + 2f * Math.ulp(1f);
    }

    private static int peakOf(AsioSampleType type) {
        return (int) ((1L << (type.significantBits() - 1)) - 1);
    }

    private static AsioSampleType[] integerFormats() {
        return Arrays.stream(AsioSampleType.values())
                .filter(type -> type.significantBits() != 0)
                .toArray(AsioSampleType[]::new);
    }

    private static AsioSampleType[] rightJustifiedInt32Formats() {
        return new AsioSampleType[] {
                AsioSampleType.INT32_MSB16, AsioSampleType.INT32_MSB18,
                AsioSampleType.INT32_MSB20, AsioSampleType.INT32_MSB24,
                AsioSampleType.INT32_LSB16, AsioSampleType.INT32_LSB18,
                AsioSampleType.INT32_LSB20, AsioSampleType.INT32_LSB24};
    }

    private MemorySegment allocate(AsioSampleType type, int frames) {
        return arena.allocate((long) frames * type.bytesPerSample());
    }

    /** Byte order taken from the ASIO constant's own name, not from the enum's fields. */
    private static boolean isLittleEndian(AsioSampleType type) {
        return type.name().contains("LSB");
    }

    /**
     * Reassembles the signed integer a driver would see, independently of
     * {@link AsioSampleType#decode}. The final shift sign-extends a 2- or
     * 3-byte container; a 4-byte one is already full width, which is exactly
     * what the right-justified assertions want to inspect.
     */
    private static int storedInteger(MemorySegment segment, AsioSampleType type, int frame) {
        int width = type.bytesPerSample();
        byte[] raw = bytesOf(segment, (long) frame * width, width);
        if (isLittleEndian(type)) {
            raw = reversed(raw);
        }
        int value = 0;
        for (byte b : raw) {
            value = (value << 8) | (b & 0xFF);
        }
        int shift = Integer.SIZE - width * Byte.SIZE;
        return value << shift >> shift;
    }

    /** The inverse of {@link #storedInteger}: lays a raw integer out byte by byte. */
    private static void writeStoredInteger(MemorySegment segment, AsioSampleType type,
                                           int frame, int value) {
        int width = type.bytesPerSample();
        long offset = (long) frame * width;
        for (int i = 0; i < width; i++) {
            int shift = isLittleEndian(type) ? i * Byte.SIZE : (width - 1 - i) * Byte.SIZE;
            segment.set(ValueLayout.JAVA_BYTE, offset + i, (byte) (value >> shift));
        }
    }

    private static byte[] bytesOf(MemorySegment segment, long offset, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = segment.get(ValueLayout.JAVA_BYTE, offset + i);
        }
        return bytes;
    }

    private static byte[] reversed(byte[] bytes) {
        byte[] flipped = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            flipped[i] = bytes[bytes.length - 1 - i];
        }
        return flipped;
    }
}
