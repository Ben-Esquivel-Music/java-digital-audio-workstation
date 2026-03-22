package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetrySuggestionTest {

    @Test
    void adjustMicPositionShouldFormatDescription() {
        var suggestion = new TelemetrySuggestion.AdjustMicPosition(
                "OH-L", new Position3D(2.0, 3.0, 2.5), "too close to wall");

        assertThat(suggestion.description()).contains("OH-L");
        assertThat(suggestion.description()).contains("too close to wall");
    }

    @Test
    void adjustMicAngleShouldFormatDescription() {
        var suggestion = new TelemetrySuggestion.AdjustMicAngle(
                "SM57", 45.0, 10.0, "not aimed at source");

        assertThat(suggestion.description()).contains("SM57");
        assertThat(suggestion.description()).contains("45.0");
    }

    @Test
    void addDampeningShouldFormatDescription() {
        var suggestion = new TelemetrySuggestion.AddDampening(
                "back wall", "RT60 too high");

        assertThat(suggestion.description()).contains("back wall");
        assertThat(suggestion.description()).contains("RT60 too high");
    }

    @Test
    void removeDampeningShouldFormatDescription() {
        var suggestion = new TelemetrySuggestion.RemoveDampening(
                "ceiling", "room is too dead");

        assertThat(suggestion.description()).contains("ceiling");
        assertThat(suggestion.description()).contains("room is too dead");
    }

    @Test
    void adjustMicPositionShouldRejectNulls() {
        assertThatThrownBy(() -> new TelemetrySuggestion.AdjustMicPosition(
                null, new Position3D(0, 0, 0), "reason"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void addDampeningShouldRejectNulls() {
        assertThatThrownBy(() -> new TelemetrySuggestion.AddDampening(null, "reason"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TelemetrySuggestion.AddDampening("wall", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSupportExhaustiveSwitchOverSuggestions() {
        TelemetrySuggestion suggestion = new TelemetrySuggestion.AddDampening("wall", "reverb");

        String result = switch (suggestion) {
            case TelemetrySuggestion.AdjustMicPosition amp -> "position";
            case TelemetrySuggestion.AdjustMicAngle ama -> "angle";
            case TelemetrySuggestion.AddDampening ad -> "add-dampening";
            case TelemetrySuggestion.RemoveDampening rd -> "remove-dampening";
        };

        assertThat(result).isEqualTo("add-dampening");
    }
}
