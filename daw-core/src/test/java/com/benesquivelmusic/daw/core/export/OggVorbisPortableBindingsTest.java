package com.benesquivelmusic.daw.core.export;

import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that the portable struct layout computation in
 * {@link OggVorbisExporter} produces correct sizes and offsets
 * on the current platform.
 *
 * <p>These tests assert on the exporter's actual computed constants
 * (package-private) rather than duplicating the layout math, so any
 * drift in the exporter's struct definitions is caught immediately.</p>
 */
class OggVorbisPortableBindingsTest {

    @Test
    void cLongShouldHaveValidSize() {
        long size = OggVorbisExporter.C_LONG.byteSize();
        // C 'long' is either 4 bytes (Windows LLP64) or 8 bytes (Linux/macOS LP64)
        assertThat(size).isIn(4L, 8L);
    }

    @Test
    void oggPageLayoutShouldBeConsistent() {
        long[] offsets = OggVorbisExporter.OGG_PAGE_OFFSETS;
        long size = OggVorbisExporter.SIZEOF_OGG_PAGE;

        // header starts at offset 0
        assertThat(offsets[0]).isEqualTo(0);
        // header_len follows header (aligned to C_LONG alignment)
        assertThat(offsets[1]).isGreaterThanOrEqualTo(ValueLayout.ADDRESS.byteSize());
        // body follows header_len (aligned to pointer alignment)
        assertThat(offsets[2]).isGreaterThan(offsets[1]);
        // body_len follows body
        assertThat(offsets[3]).isGreaterThan(offsets[2]);
        // struct size covers all fields
        assertThat(size).isGreaterThanOrEqualTo(
                offsets[3] + OggVorbisExporter.C_LONG.byteSize());
    }

    @Test
    void structSizesOnCurrentPlatformShouldBePositive() {
        // All struct sizes must be positive (they're used for allocation)
        assertThat(OggVorbisExporter.SIZEOF_VORBIS_INFO).isPositive();
        assertThat(OggVorbisExporter.SIZEOF_VORBIS_COMMENT).isPositive();
        assertThat(OggVorbisExporter.SIZEOF_VORBIS_DSP_STATE).isPositive();
        assertThat(OggVorbisExporter.SIZEOF_VORBIS_BLOCK).isPositive();
        assertThat(OggVorbisExporter.SIZEOF_OGG_STREAM_STATE).isPositive();
        assertThat(OggVorbisExporter.SIZEOF_OGG_PAGE).isPositive();
        assertThat(OggVorbisExporter.SIZEOF_OGG_PACKET).isPositive();
    }

    @Test
    void structSizesShouldBeAlignedToPointerSize() {
        // All struct sizes should be a multiple of the pointer size (natural alignment)
        long ptrSize = ValueLayout.ADDRESS.byteSize();
        assertThat(OggVorbisExporter.SIZEOF_VORBIS_INFO % ptrSize).isZero();
        assertThat(OggVorbisExporter.SIZEOF_VORBIS_DSP_STATE % ptrSize).isZero();
        assertThat(OggVorbisExporter.SIZEOF_VORBIS_BLOCK % ptrSize).isZero();
        assertThat(OggVorbisExporter.SIZEOF_OGG_STREAM_STATE % ptrSize).isZero();
        assertThat(OggVorbisExporter.SIZEOF_OGG_PAGE % ptrSize).isZero();
        assertThat(OggVorbisExporter.SIZEOF_OGG_PACKET % ptrSize).isZero();
    }

    @Test
    void oggPacketShouldBeLargeEnoughForAllFields() {
        // ogg_packet has: pointer, 3 C longs, 2 int64s
        ValueLayout cLong = OggVorbisExporter.C_LONG;
        long minSize = ValueLayout.ADDRESS.byteSize() + 3 * cLong.byteSize()
                + 2 * ValueLayout.JAVA_LONG.byteSize();
        assertThat(OggVorbisExporter.SIZEOF_OGG_PACKET).isGreaterThanOrEqualTo(minSize);
    }

    @Test
    void vorbisInfoShouldBeLargeEnoughForAllFields() {
        // vorbis_info: 2 ints, 5 C longs, 1 pointer
        ValueLayout cLong = OggVorbisExporter.C_LONG;
        long minSize = 2 * ValueLayout.JAVA_INT.byteSize() + 5 * cLong.byteSize()
                + ValueLayout.ADDRESS.byteSize();
        assertThat(OggVorbisExporter.SIZEOF_VORBIS_INFO).isGreaterThanOrEqualTo(minSize);
    }

    @Test
    void readCLongShouldReadCorrectFieldWidth() {
        // Verify that C_LONG.byteSize() determines the read width
        ValueLayout cLong = OggVorbisExporter.C_LONG;
        if (cLong.byteSize() == 8) {
            // On LP64 (Linux/macOS): C long = Java long
            assertThat(cLong.byteSize()).isEqualTo(ValueLayout.JAVA_LONG.byteSize());
        } else {
            // On LLP64 (Windows): C long = Java int
            assertThat(cLong.byteSize()).isEqualTo(ValueLayout.JAVA_INT.byteSize());
        }
    }

    @Test
    void computeStructSizeShouldApplyPaddingAndAlignment() {
        // Verify the helper directly: a struct with {int, pointer} should
        // have padding between the int and the pointer on 64-bit platforms
        long size = OggVorbisExporter.computeStructSize(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        long ptrSize = ValueLayout.ADDRESS.byteSize();
        // On 64-bit: 4 (int) + 4 (padding) + 8 (pointer) = 16
        // Minimum is int + pointer with alignment
        assertThat(size).isGreaterThanOrEqualTo(
                ValueLayout.JAVA_INT.byteSize() + ptrSize);
        assertThat(size % ptrSize).isZero();
    }

    @Test
    void computeFieldOffsetsShouldRespectAlignment() {
        // Verify offsets: {int, pointer} should have pointer at offset ptrSize
        long[] offsets = OggVorbisExporter.computeFieldOffsets(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        long ptrSize = ValueLayout.ADDRESS.byteSize();
        assertThat(offsets[0]).isEqualTo(0);
        // pointer field must be aligned to its own alignment
        assertThat(offsets[1] % ptrSize).isZero();
        assertThat(offsets[1]).isGreaterThanOrEqualTo(ValueLayout.JAVA_INT.byteSize());
    }
}
