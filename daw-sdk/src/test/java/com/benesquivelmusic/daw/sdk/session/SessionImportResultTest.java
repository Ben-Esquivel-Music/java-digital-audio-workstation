package com.benesquivelmusic.daw.sdk.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionImportResultTest {

    @Test
    void shouldCreateWithSessionDataAndWarnings() {
        var sessionData = new SessionData(
                "Test Project", 120.0, 4, 4, 44100.0, List.of());
        var warnings = List.of("Plugin X not supported");

        var result = new SessionImportResult(sessionData, warnings);

        assertThat(result.sessionData()).isEqualTo(sessionData);
        assertThat(result.warnings()).containsExactly("Plugin X not supported");
    }

    @Test
    void shouldCreateWithEmptyWarnings() {
        var sessionData = new SessionData(
                "Test", 140.0, 3, 4, 48000.0, List.of());

        var result = new SessionImportResult(sessionData, List.of());

        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void shouldRejectNullSessionData() {
        assertThatThrownBy(() -> new SessionImportResult(null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullWarnings() {
        var sessionData = new SessionData(
                "Test", 120.0, 4, 4, 44100.0, List.of());
        assertThatThrownBy(() -> new SessionImportResult(sessionData, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnDefensiveCopyOfWarnings() {
        var sessionData = new SessionData(
                "Test", 120.0, 4, 4, 44100.0, List.of());
        var result = new SessionImportResult(sessionData, List.of("warn1"));

        assertThatThrownBy(() -> result.warnings().add("mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
