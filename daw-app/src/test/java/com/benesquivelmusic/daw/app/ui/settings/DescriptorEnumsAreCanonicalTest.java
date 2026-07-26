package com.benesquivelmusic.daw.app.ui.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 305 — sentinel against vocabulary drift (the {@code DawViewTest}
 * enum-shape idiom): {@link Scope} has exactly the four §3.2 values,
 * {@link ApplyClass} exactly the three §6.4 values, and
 * {@link ControlKind} exactly the seven §3.4 values, each in the book's
 * documented order. Later stages never invent a synonym.
 */
class DescriptorEnumsAreCanonicalTest {

    @Test
    void scopeShouldHaveExactlyTheFourBookValuesInOrder() {
        assertThat(Scope.values()).hasSize(4);
        assertThat(Scope.values())
                .containsExactly(Scope.APPLICATION, Scope.PROJECT_DEFAULTS,
                        Scope.THIS_PROJECT, Scope.AUDIO_DEVICE);
    }

    @Test
    void applyClassShouldHaveExactlyTheThreeBookValuesInOrder() {
        assertThat(ApplyClass.values()).hasSize(3);
        assertThat(ApplyClass.values())
                .containsExactly(ApplyClass.LIVE, ApplyClass.ENGINE_RECONFIGURE,
                        ApplyClass.RESTART_REQUIRED);
    }

    @Test
    void controlKindShouldHaveExactlyTheSevenBookValuesInOrder() {
        assertThat(ControlKind.values()).hasSize(7);
        assertThat(ControlKind.values())
                .containsExactly(ControlKind.TOGGLE, ControlKind.CHOICE,
                        ControlKind.SLIDER, ControlKind.STEPPER, ControlKind.TEXT,
                        ControlKind.PATH, ControlKind.KEY_CAPTURE);
    }

    @ParameterizedTest
    @EnumSource(Scope.class)
    void scopeValueOfShouldRoundTrip(Scope scope) {
        assertThat(Scope.valueOf(scope.name())).isEqualTo(scope);
    }

    @ParameterizedTest
    @EnumSource(ApplyClass.class)
    void applyClassValueOfShouldRoundTrip(ApplyClass applyClass) {
        assertThat(ApplyClass.valueOf(applyClass.name())).isEqualTo(applyClass);
    }

    @ParameterizedTest
    @EnumSource(ControlKind.class)
    void controlKindValueOfShouldRoundTrip(ControlKind controlKind) {
        assertThat(ControlKind.valueOf(controlKind.name())).isEqualTo(controlKind);
    }
}
