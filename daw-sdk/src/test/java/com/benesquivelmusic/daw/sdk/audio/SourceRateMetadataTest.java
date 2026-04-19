package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceRateMetadataTest {

    @Test
    void constructsFromBasicFields() {
        SourceRateMetadata m = new SourceRateMetadata(44_100, 2, 88_200);
        assertThat(m.nativeRateHz()).isEqualTo(44_100);
        assertThat(m.channels()).isEqualTo(2);
        assertThat(m.framesPerChannel()).isEqualTo(88_200);
    }

    @Test
    void ofRateChannelsDefaultsFramesToZero() {
        SourceRateMetadata m = SourceRateMetadata.of(48_000, 1);
        assertThat(m.framesPerChannel()).isZero();
        assertThat(m.durationSeconds()).isZero();
    }

    @Test
    void ofEnumResolvesRate() {
        SourceRateMetadata m = SourceRateMetadata.of(SampleRate.HZ_96000, 2, 192_000);
        assertThat(m.nativeRateHz()).isEqualTo(96_000);
        assertThat(m.durationSeconds()).isEqualTo(2.0);
    }

    @Test
    void rejectsNonPositiveRate() {
        assertThatThrownBy(() -> new SourceRateMetadata(0, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveChannels() {
        assertThatThrownBy(() -> new SourceRateMetadata(44_100, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeFrames() {
        assertThatThrownBy(() -> new SourceRateMetadata(44_100, 2, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresConversionReturnsFalseWhenRatesMatch() {
        SourceRateMetadata m = SourceRateMetadata.of(48_000, 2);
        assertThat(m.requiresConversion(48_000)).isFalse();
    }

    @Test
    void requiresConversionReturnsTrueWhenRatesDiffer() {
        SourceRateMetadata m = SourceRateMetadata.of(48_000, 2);
        assertThat(m.requiresConversion(44_100)).isTrue();
    }

    @Test
    void requiresConversionRejectsNonPositiveSessionRate() {
        SourceRateMetadata m = SourceRateMetadata.of(48_000, 2);
        assertThatThrownBy(() -> m.requiresConversion(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void badgeLabelRendersWhenConverting() {
        SourceRateMetadata m = SourceRateMetadata.of(48_000, 2);
        assertThat(m.badgeLabel(44_100)).isEqualTo("↻ 48→44.1");
    }

    @Test
    void badgeLabelEmptyWhenMatching() {
        SourceRateMetadata m = SourceRateMetadata.of(48_000, 2);
        assertThat(m.badgeLabel(48_000)).isEmpty();
    }

    @Test
    void badgeLabelFormatsFractionalKhz() {
        SourceRateMetadata m = new SourceRateMetadata(88_200, 2, 0);
        assertThat(m.badgeLabel(44_100)).isEqualTo("↻ 88.2→44.1");
    }

    @Test
    void durationSecondsComputesFromFrames() {
        SourceRateMetadata m = new SourceRateMetadata(48_000, 2, 96_000);
        assertThat(m.durationSeconds()).isEqualTo(2.0);
    }

    @Test
    void recordsAreValueEqual() {
        SourceRateMetadata a = new SourceRateMetadata(44_100, 2, 100);
        SourceRateMetadata b = new SourceRateMetadata(44_100, 2, 100);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
