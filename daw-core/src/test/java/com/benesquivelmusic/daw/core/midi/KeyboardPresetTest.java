package com.benesquivelmusic.daw.core.midi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyboardPresetTest {

    @Test
    void shouldCreateValidPreset() {
        KeyboardPreset preset = new KeyboardPreset(
                "Test Piano", 0, 0, 2, 6, 0, 100, VelocityCurve.LINEAR);
        assertThat(preset.name()).isEqualTo("Test Piano");
        assertThat(preset.bank()).isZero();
        assertThat(preset.program()).isZero();
        assertThat(preset.lowestOctave()).isEqualTo(2);
        assertThat(preset.highestOctave()).isEqualTo(6);
        assertThat(preset.transpose()).isZero();
        assertThat(preset.defaultVelocity()).isEqualTo(100);
        assertThat(preset.velocityCurve()).isEqualTo(VelocityCurve.LINEAR);
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new KeyboardPreset(
                null, 0, 0, 2, 6, 0, 100, VelocityCurve.LINEAR))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "  ", 0, 0, 2, 6, 0, 100, VelocityCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void shouldRejectNegativeBank() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "Test", -1, 0, 2, 6, 0, 100, VelocityCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank");
    }

    @Test
    void shouldRejectBankExceedingMax() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "Test", 16384, 0, 2, 6, 0, 100, VelocityCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank");
    }

    @Test
    void shouldRejectNegativeProgram() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "Test", 0, -1, 2, 6, 0, 100, VelocityCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("program");
    }

    @Test
    void shouldRejectProgramExceedingMax() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "Test", 0, 128, 2, 6, 0, 100, VelocityCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("program");
    }

    @Test
    void shouldRejectLowestOctaveBelowMin() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "Test", 0, 0, -2, 6, 0, 100, VelocityCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowestOctave");
    }

    @Test
    void shouldRejectHighestOctaveAboveMax() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "Test", 0, 0, 2, 10, 0, 100, VelocityCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("highestOctave");
    }

    @Test
    void shouldRejectHighestOctaveBelowLowest() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "Test", 0, 0, 5, 3, 0, 100, VelocityCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("highestOctave");
    }

    @Test
    void shouldRejectTransposeBelowMin() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "Test", 0, 0, 2, 6, -49, 100, VelocityCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transpose");
    }

    @Test
    void shouldRejectTransposeAboveMax() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "Test", 0, 0, 2, 6, 49, 100, VelocityCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transpose");
    }

    @Test
    void shouldRejectVelocityBelowMin() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "Test", 0, 0, 2, 6, 0, 0, VelocityCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultVelocity");
    }

    @Test
    void shouldRejectVelocityAboveMax() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "Test", 0, 0, 2, 6, 0, 128, VelocityCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultVelocity");
    }

    @Test
    void shouldRejectNullVelocityCurve() {
        assertThatThrownBy(() -> new KeyboardPreset(
                "Test", 0, 0, 2, 6, 0, 100, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Factory presets ────────────────────────────────────────────────

    @Test
    void grandPianoShouldHaveCorrectProgram() {
        KeyboardPreset piano = KeyboardPreset.grandPiano();
        assertThat(piano.name()).isEqualTo("Grand Piano");
        assertThat(piano.bank()).isZero();
        assertThat(piano.program()).isZero();
        assertThat(piano.velocityCurve()).isEqualTo(VelocityCurve.LINEAR);
    }

    @Test
    void electricPianoShouldHaveCorrectProgram() {
        KeyboardPreset ep = KeyboardPreset.electricPiano();
        assertThat(ep.name()).isEqualTo("Electric Piano");
        assertThat(ep.program()).isEqualTo(4);
        assertThat(ep.velocityCurve()).isEqualTo(VelocityCurve.SOFT);
    }

    @Test
    void organShouldHaveFixedVelocity() {
        KeyboardPreset organ = KeyboardPreset.organ();
        assertThat(organ.name()).isEqualTo("Organ");
        assertThat(organ.program()).isEqualTo(19);
        assertThat(organ.velocityCurve()).isEqualTo(VelocityCurve.FIXED);
    }

    @Test
    void stringsShouldHaveSoftCurve() {
        KeyboardPreset strings = KeyboardPreset.strings();
        assertThat(strings.name()).isEqualTo("Strings");
        assertThat(strings.program()).isEqualTo(48);
        assertThat(strings.velocityCurve()).isEqualTo(VelocityCurve.SOFT);
    }

    @Test
    void synthLeadShouldHaveHardCurve() {
        KeyboardPreset lead = KeyboardPreset.synthLead();
        assertThat(lead.name()).isEqualTo("Synth Lead");
        assertThat(lead.program()).isEqualTo(80);
        assertThat(lead.velocityCurve()).isEqualTo(VelocityCurve.HARD);
    }

    @Test
    void synthPadShouldHaveSoftCurve() {
        KeyboardPreset pad = KeyboardPreset.synthPad();
        assertThat(pad.name()).isEqualTo("Synth Pad");
        assertThat(pad.program()).isEqualTo(88);
    }

    @Test
    void bassShouldHaveLinearCurve() {
        KeyboardPreset bass = KeyboardPreset.bass();
        assertThat(bass.name()).isEqualTo("Bass");
        assertThat(bass.program()).isEqualTo(32);
        assertThat(bass.velocityCurve()).isEqualTo(VelocityCurve.LINEAR);
    }

    @Test
    void choirShouldHaveSoftCurve() {
        KeyboardPreset choir = KeyboardPreset.choir();
        assertThat(choir.name()).isEqualTo("Choir");
        assertThat(choir.program()).isEqualTo(52);
        assertThat(choir.velocityCurve()).isEqualTo(VelocityCurve.SOFT);
    }

    @Test
    void factoryPresetsShouldContainAllBuiltIns() {
        KeyboardPreset[] presets = KeyboardPreset.factoryPresets();
        assertThat(presets).hasSize(8);
        assertThat(presets[0].name()).isEqualTo("Grand Piano");
        assertThat(presets[7].name()).isEqualTo("Choir");
    }

    // ── With-methods ───────────────────────────────────────────────────

    @Test
    void withTransposeShouldReturnUpdatedPreset() {
        KeyboardPreset piano = KeyboardPreset.grandPiano();
        KeyboardPreset transposed = piano.withTranspose(12);
        assertThat(transposed.transpose()).isEqualTo(12);
        assertThat(transposed.name()).isEqualTo(piano.name());
        assertThat(transposed.program()).isEqualTo(piano.program());
    }

    @Test
    void withDefaultVelocityShouldReturnUpdatedPreset() {
        KeyboardPreset piano = KeyboardPreset.grandPiano();
        KeyboardPreset loud = piano.withDefaultVelocity(127);
        assertThat(loud.defaultVelocity()).isEqualTo(127);
        assertThat(loud.name()).isEqualTo(piano.name());
    }

    @Test
    void withVelocityCurveShouldReturnUpdatedPreset() {
        KeyboardPreset piano = KeyboardPreset.grandPiano();
        KeyboardPreset hard = piano.withVelocityCurve(VelocityCurve.HARD);
        assertThat(hard.velocityCurve()).isEqualTo(VelocityCurve.HARD);
        assertThat(hard.name()).isEqualTo(piano.name());
    }

    @Test
    void withOctaveRangeShouldReturnUpdatedPreset() {
        KeyboardPreset piano = KeyboardPreset.grandPiano();
        KeyboardPreset narrowed = piano.withOctaveRange(3, 5);
        assertThat(narrowed.lowestOctave()).isEqualTo(3);
        assertThat(narrowed.highestOctave()).isEqualTo(5);
        assertThat(narrowed.name()).isEqualTo(piano.name());
    }

    // ── Edge cases / boundary values ───────────────────────────────────

    @Test
    void shouldAcceptMinimumOctaveRange() {
        KeyboardPreset preset = new KeyboardPreset(
                "Narrow", 0, 0, -1, -1, 0, 1, VelocityCurve.LINEAR);
        assertThat(preset.lowestOctave()).isEqualTo(-1);
        assertThat(preset.highestOctave()).isEqualTo(-1);
    }

    @Test
    void shouldAcceptMaxTranspose() {
        KeyboardPreset preset = new KeyboardPreset(
                "High", 0, 0, 2, 6, 48, 100, VelocityCurve.LINEAR);
        assertThat(preset.transpose()).isEqualTo(48);
    }

    @Test
    void shouldAcceptMinTranspose() {
        KeyboardPreset preset = new KeyboardPreset(
                "Low", 0, 0, 2, 6, -48, 100, VelocityCurve.LINEAR);
        assertThat(preset.transpose()).isEqualTo(-48);
    }

    @Test
    void shouldAcceptMaxBankAndProgram() {
        KeyboardPreset preset = new KeyboardPreset(
                "Max", 16383, 127, 0, 9, 0, 127, VelocityCurve.FIXED);
        assertThat(preset.bank()).isEqualTo(16383);
        assertThat(preset.program()).isEqualTo(127);
    }

    @Test
    void constantsShouldHaveExpectedValues() {
        assertThat(KeyboardPreset.MAX_BANK).isEqualTo(16383);
        assertThat(KeyboardPreset.MAX_PROGRAM).isEqualTo(127);
        assertThat(KeyboardPreset.MIN_OCTAVE).isEqualTo(-1);
        assertThat(KeyboardPreset.MAX_OCTAVE).isEqualTo(9);
        assertThat(KeyboardPreset.MAX_TRANSPOSE).isEqualTo(48);
        assertThat(KeyboardPreset.MAX_VELOCITY).isEqualTo(127);
    }
}
