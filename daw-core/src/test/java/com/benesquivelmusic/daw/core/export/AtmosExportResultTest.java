package com.benesquivelmusic.daw.core.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AtmosExportResultTest {

    @Test
    void shouldCreateSuccessResult() {
        AtmosExportResult result = AtmosExportResult.success();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    void shouldCreateSuccessResultWithWarnings() {
        List<String> warnings = List.of("Warning 1", "Warning 2");
        AtmosExportResult result = AtmosExportResult.success(warnings);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).hasSize(2);
        assertThat(result.getWarnings()).containsExactly("Warning 1", "Warning 2");
    }

    @Test
    void shouldCreateFailureResult() {
        List<String> errors = List.of("Error 1");
        AtmosExportResult result = AtmosExportResult.failure(errors);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).containsExactly("Error 1");
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    void shouldCreateFailureResultWithWarnings() {
        List<String> errors = List.of("Error 1");
        List<String> warnings = List.of("Warning 1");
        AtmosExportResult result = AtmosExportResult.failure(errors, warnings);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).containsExactly("Error 1");
        assertThat(result.getWarnings()).containsExactly("Warning 1");
    }

    @Test
    void shouldReturnUnmodifiableErrors() {
        AtmosExportResult result = AtmosExportResult.failure(List.of("Error"));

        assertThat(result.getErrors()).isUnmodifiable();
    }

    @Test
    void shouldReturnUnmodifiableWarnings() {
        AtmosExportResult result = AtmosExportResult.success(List.of("Warning"));

        assertThat(result.getWarnings()).isUnmodifiable();
    }
}
