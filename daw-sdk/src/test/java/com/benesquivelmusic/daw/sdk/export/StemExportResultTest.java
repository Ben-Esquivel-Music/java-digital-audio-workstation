package com.benesquivelmusic.daw.sdk.export;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StemExportResultTest {

    private static final AudioExportConfig CONFIG =
            new AudioExportConfig(AudioExportFormat.WAV, 44100, 24, DitherType.NONE);

    @Test
    void shouldReportAllSucceededWhenAllPass() {
        List<ExportResult> results = List.of(
                new ExportResult(CONFIG, Path.of("a.wav"), true, "ok", 100),
                new ExportResult(CONFIG, Path.of("b.wav"), true, "ok", 150)
        );
        StemExportResult stemResult = new StemExportResult(results, 250);

        assertThat(stemResult.allSucceeded()).isTrue();
        assertThat(stemResult.successCount()).isEqualTo(2);
        assertThat(stemResult.totalDurationMs()).isEqualTo(250);
    }

    @Test
    void shouldReportNotAllSucceededWhenOneFails() {
        List<ExportResult> results = List.of(
                new ExportResult(CONFIG, Path.of("a.wav"), true, "ok", 100),
                new ExportResult(CONFIG, Path.of("b.wav"), false, "error", 50)
        );
        StemExportResult stemResult = new StemExportResult(results, 150);

        assertThat(stemResult.allSucceeded()).isFalse();
        assertThat(stemResult.successCount()).isEqualTo(1);
    }

    @Test
    void shouldHandleEmptyResults() {
        StemExportResult stemResult = new StemExportResult(List.of(), 0);

        assertThat(stemResult.allSucceeded()).isTrue();
        assertThat(stemResult.successCount()).isEqualTo(0);
        assertThat(stemResult.trackResults()).isEmpty();
    }

    @Test
    void shouldReturnUnmodifiableResults() {
        StemExportResult stemResult = new StemExportResult(List.of(), 0);

        assertThatThrownBy(() -> stemResult.trackResults().add(
                new ExportResult(CONFIG, Path.of("x.wav"), true, "ok", 10)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullTrackResults() {
        assertThatThrownBy(() -> new StemExportResult(null, 0))
                .isInstanceOf(NullPointerException.class);
    }
}
