package com.benesquivelmusic.daw.sdk.session;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionExportResultTest {

    @Test
    void shouldCreateWithOutputPathAndWarnings() {
        var path = Path.of("/tmp/output/session.dawproject");
        var result = new SessionExportResult(path, List.of("Automation not exported"));

        assertThat(result.outputPath()).isEqualTo(path);
        assertThat(result.warnings()).containsExactly("Automation not exported");
    }

    @Test
    void shouldCreateWithEmptyWarnings() {
        var path = Path.of("/tmp/session.dawproject");
        var result = new SessionExportResult(path, List.of());

        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void shouldRejectNullOutputPath() {
        assertThatThrownBy(() -> new SessionExportResult(null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullWarnings() {
        assertThatThrownBy(() -> new SessionExportResult(Path.of("/tmp/x"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnDefensiveCopyOfWarnings() {
        var result = new SessionExportResult(Path.of("/tmp/x"), List.of("warn1"));

        assertThatThrownBy(() -> result.warnings().add("mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
