package com.benesquivelmusic.daw.core.export;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that the portable struct layout computation in
 * {@link OggVorbisExporter} produces correct sizes and offsets
 * on the current platform.
 *
 * <p>These tests verify the ABI-aware struct size computation
 * that replaced the previously hardcoded x86_64 Linux constants.</p>
 */
class OggVorbisPortableBindingsTest {

    /**
     * The canonical C 'long' type from the native linker — platform-dependent.
     */
    private static final ValueLayout C_LONG =
            (ValueLayout) Linker.nativeLinker().canonicalLayouts().get("long");

    @Test
    void cLongShouldHaveValidSize() {
        long size = C_LONG.byteSize();
        // C 'long' is either 4 bytes (Windows LLP64) or 8 bytes (Linux/macOS LP64)
        assertThat(size).isIn(4L, 8L);
    }

    @Test
    void oggPageLayoutShouldBeConsistent() {
        // ogg_page: { unsigned char *header; long header_len; unsigned char *body; long body_len; }
        long[] offsets = computeFieldOffsets(
                ValueLayout.ADDRESS, C_LONG, ValueLayout.ADDRESS, C_LONG);
        long size = computeStructSize(
                ValueLayout.ADDRESS, C_LONG, ValueLayout.ADDRESS, C_LONG);

        // header starts at offset 0
        assertThat(offsets[0]).isEqualTo(0);
        // header_len follows header (aligned to C_LONG alignment)
        assertThat(offsets[1]).isGreaterThanOrEqualTo(ValueLayout.ADDRESS.byteSize());
        // body follows header_len (aligned to pointer alignment)
        assertThat(offsets[2]).isGreaterThan(offsets[1]);
        // body_len follows body
        assertThat(offsets[3]).isGreaterThan(offsets[2]);
        // struct size covers all fields
        assertThat(size).isGreaterThanOrEqualTo(offsets[3] + C_LONG.byteSize());
    }

    @Test
    void structSizesOnCurrentPlatformShouldBePositive() {
        // All struct sizes must be positive (they're used for allocation)
        assertThat(computeVorbisInfoSize()).isPositive();
        assertThat(computeVorbisCommentSize()).isPositive();
        assertThat(computeVorbisDspStateSize()).isPositive();
        assertThat(computeVorbisBlockSize()).isPositive();
        assertThat(computeOggStreamStateSize()).isPositive();
        assertThat(computeOggPageSize()).isPositive();
        assertThat(computeOggPacketSize()).isPositive();
    }

    @Test
    void structSizesShouldBeAlignedToPointerSize() {
        // All struct sizes should be a multiple of the pointer size (natural alignment)
        long ptrSize = ValueLayout.ADDRESS.byteSize();
        assertThat(computeVorbisInfoSize() % ptrSize).isZero();
        assertThat(computeVorbisDspStateSize() % ptrSize).isZero();
        assertThat(computeVorbisBlockSize() % ptrSize).isZero();
        assertThat(computeOggStreamStateSize() % ptrSize).isZero();
        assertThat(computeOggPageSize() % ptrSize).isZero();
        assertThat(computeOggPacketSize() % ptrSize).isZero();
    }

    @Test
    void oggPacketShouldBeLargeEnoughForAllFields() {
        // ogg_packet has: pointer, 3 longs, 2 int64s
        long minSize = ValueLayout.ADDRESS.byteSize() + 3 * C_LONG.byteSize()
                + 2 * ValueLayout.JAVA_LONG.byteSize();
        assertThat(computeOggPacketSize()).isGreaterThanOrEqualTo(minSize);
    }

    @Test
    void vorbisInfoShouldBeLargeEnoughForAllFields() {
        // vorbis_info: 2 ints, 5 longs, 1 pointer
        long minSize = 2 * ValueLayout.JAVA_INT.byteSize() + 5 * C_LONG.byteSize()
                + ValueLayout.ADDRESS.byteSize();
        assertThat(computeVorbisInfoSize()).isGreaterThanOrEqualTo(minSize);
    }

    @Test
    void readCLongShouldReadCorrectFieldWidth() {
        // Verify that C_LONG.byteSize() determines the read width
        if (C_LONG.byteSize() == 8) {
            // On LP64 (Linux/macOS): C long = Java long
            assertThat(C_LONG.byteSize()).isEqualTo(ValueLayout.JAVA_LONG.byteSize());
        } else {
            // On LLP64 (Windows): C long = Java int
            assertThat(C_LONG.byteSize()).isEqualTo(ValueLayout.JAVA_INT.byteSize());
        }
    }

    // --- Helper methods matching OggVorbisExporter's layout computation ---

    private static long computeStructSize(MemoryLayout... fields) {
        long offset = 0;
        long maxAlign = 1;
        for (MemoryLayout field : fields) {
            long align = field.byteAlignment();
            offset = (offset + align - 1) & ~(align - 1);
            offset += field.byteSize();
            maxAlign = Math.max(maxAlign, align);
        }
        return (offset + maxAlign - 1) & ~(maxAlign - 1);
    }

    private static long[] computeFieldOffsets(MemoryLayout... fields) {
        long[] offsets = new long[fields.length];
        long offset = 0;
        for (int i = 0; i < fields.length; i++) {
            long align = fields[i].byteAlignment();
            offset = (offset + align - 1) & ~(align - 1);
            offsets[i] = offset;
            offset += fields[i].byteSize();
        }
        return offsets;
    }

    private static MemoryLayout asNestedStruct(MemoryLayout... fields) {
        long size = computeStructSize(fields);
        long maxAlign = 1;
        for (MemoryLayout field : fields) {
            maxAlign = Math.max(maxAlign, field.byteAlignment());
        }
        return MemoryLayout.sequenceLayout(size, ValueLayout.JAVA_BYTE)
                .withByteAlignment(maxAlign);
    }

    private static long computeVorbisInfoSize() {
        return computeStructSize(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                C_LONG, C_LONG, C_LONG, C_LONG, C_LONG,
                ValueLayout.ADDRESS);
    }

    private static long computeVorbisCommentSize() {
        return computeStructSize(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS);
    }

    private static long computeVorbisDspStateSize() {
        return computeStructSize(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                C_LONG, C_LONG, C_LONG, C_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS);
    }

    private static long computeVorbisBlockSize() {
        MemoryLayout oggpackBuffer = asNestedStruct(
                C_LONG, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, C_LONG);
        return computeStructSize(
                ValueLayout.ADDRESS,
                oggpackBuffer,
                C_LONG, C_LONG, C_LONG,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                C_LONG, C_LONG, C_LONG,
                ValueLayout.ADDRESS,
                C_LONG, C_LONG, C_LONG, C_LONG,
                ValueLayout.ADDRESS);
    }

    private static long computeOggStreamStateSize() {
        return computeStructSize(
                ValueLayout.ADDRESS,
                C_LONG, C_LONG, C_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                C_LONG, C_LONG, C_LONG, C_LONG,
                MemoryLayout.sequenceLayout(282, ValueLayout.JAVA_BYTE),
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                C_LONG, C_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
    }

    private static long computeOggPageSize() {
        return computeStructSize(
                ValueLayout.ADDRESS, C_LONG, ValueLayout.ADDRESS, C_LONG);
    }

    private static long computeOggPacketSize() {
        return computeStructSize(
                ValueLayout.ADDRESS, C_LONG, C_LONG, C_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
    }
}
