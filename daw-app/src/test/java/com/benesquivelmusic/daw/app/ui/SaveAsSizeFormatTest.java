package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Save As's sizes (the studio engineer's ruling Q2): the disk
 * cell's base, 1024, and its units — whole {@code KB} below 1 MB, whole
 * {@code MB} below 1 GB, one decimal from GB up — each number on its own,
 * {@code Locale.ROOT}; two decimals for both of the free-space refusal's sizes
 * when they would read the same.
 */
class SaveAsSizeFormatTest {

    private static final long KIB = 1024L;
    private static final long MIB = KIB * 1024L;
    private static final long GIB = MIB * 1024L;
    private static final long TIB = GIB * 1024L;

    @Test
    void eachSizeIsInTheUnitItsMagnitudeTakes() {
        assertThat(SaveAsSizeFormat.format(0)).isEqualTo("0 KB");
        assertThat(SaveAsSizeFormat.format(2 * KIB)).isEqualTo("2 KB");
        assertThat(SaveAsSizeFormat.format(MIB - KIB)).as("below 1 MB: whole KB").isEqualTo("1023 KB");
        assertThat(SaveAsSizeFormat.format(412 * MIB)).isEqualTo("412 MB");
        assertThat(SaveAsSizeFormat.format(GIB * 32 / 10)).as("Q2's example").isEqualTo("3.2 GB");
        assertThat(SaveAsSizeFormat.format(GIB)).isEqualTo("1.0 GB");
        assertThat(SaveAsSizeFormat.format(TIB * 15 / 10)).isEqualTo("1.5 TB");
    }

    /** The studio engineer's stage-5 N2: a size that rounds to a unit's ceiling reads in the next unit. */
    @Test
    void aSizeThatRoundsToAUnitsCeilingReadsInTheNextUnit() {
        assertThat(SaveAsSizeFormat.format(MIB - 1)).isEqualTo("1 MB");
        assertThat(SaveAsSizeFormat.format(GIB - 1)).isEqualTo("1.0 GB");
        assertThat(SaveAsSizeFormat.format(TIB - 1)).isEqualTo("1.0 TB");
        assertThat(SaveAsSizeFormat.neededAndUsable(GIB - 1, GIB - 2)).as("and with two decimals")
                .containsExactly("1.00 GB", "1.00 GB");
    }

    @Test
    void theRefusalsSizesGetTwoDecimalsOnlyWhenTheyWouldReadTheSame() {
        assertThat(SaveAsSizeFormat.neededAndUsable(GIB * 32 / 10, GIB * 11 / 10)).containsExactly("3.2 GB", "1.1 GB");
        assertThat(SaveAsSizeFormat.neededAndUsable(3_435_973_837L, 3_420_000_000L))
                .containsExactly("3.20 GB", "3.19 GB");
        assertThat(SaveAsSizeFormat.neededAndUsable(MIB * 5 + KIB * 300, MIB * 5 + KIB * 100))
                .as("whole MB too").containsExactly("5.29 MB", "5.10 MB");
    }
}
