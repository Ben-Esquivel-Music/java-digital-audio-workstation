package com.benesquivelmusic.daw.core.midi;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoundFontAssignmentTest {

    private static final Path SF2_PATH = Path.of("/sounds/GeneralUser.sf2");

    @Test
    void shouldCreateAssignment() {
        SoundFontAssignment assignment = new SoundFontAssignment(SF2_PATH, 0, 0, "Acoustic Grand Piano");

        assertThat(assignment.soundFontPath()).isEqualTo(SF2_PATH);
        assertThat(assignment.bank()).isZero();
        assertThat(assignment.program()).isZero();
        assertThat(assignment.presetName()).isEqualTo("Acoustic Grand Piano");
    }

    @Test
    void shouldAcceptMaxBankValue() {
        SoundFontAssignment assignment = new SoundFontAssignment(SF2_PATH, 16383, 0, "Test");
        assertThat(assignment.bank()).isEqualTo(16383);
    }

    @Test
    void shouldAcceptMaxProgramValue() {
        SoundFontAssignment assignment = new SoundFontAssignment(SF2_PATH, 0, 127, "Test");
        assertThat(assignment.program()).isEqualTo(127);
    }

    @Test
    void shouldRejectNullPath() {
        assertThatThrownBy(() -> new SoundFontAssignment(null, 0, 0, "Test"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("soundFontPath");
    }

    @Test
    void shouldRejectNegativeBank() {
        assertThatThrownBy(() -> new SoundFontAssignment(SF2_PATH, -1, 0, "Test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank");
    }

    @Test
    void shouldRejectBankAboveMax() {
        assertThatThrownBy(() -> new SoundFontAssignment(SF2_PATH, 16384, 0, "Test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank");
    }

    @Test
    void shouldRejectNegativeProgram() {
        assertThatThrownBy(() -> new SoundFontAssignment(SF2_PATH, 0, -1, "Test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("program");
    }

    @Test
    void shouldRejectProgramAboveMax() {
        assertThatThrownBy(() -> new SoundFontAssignment(SF2_PATH, 0, 128, "Test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("program");
    }

    @Test
    void shouldRejectNullPresetName() {
        assertThatThrownBy(() -> new SoundFontAssignment(SF2_PATH, 0, 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("presetName");
    }

    @Test
    void shouldRejectBlankPresetName() {
        assertThatThrownBy(() -> new SoundFontAssignment(SF2_PATH, 0, 0, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("presetName");
    }

    @Test
    void shouldSupportRecordEquality() {
        SoundFontAssignment a = new SoundFontAssignment(SF2_PATH, 0, 25, "Steel Guitar");
        SoundFontAssignment b = new SoundFontAssignment(SF2_PATH, 0, 25, "Steel Guitar");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldNotEqualDifferentAssignment() {
        SoundFontAssignment a = new SoundFontAssignment(SF2_PATH, 0, 0, "Piano");
        SoundFontAssignment b = new SoundFontAssignment(SF2_PATH, 0, 25, "Steel Guitar");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void shouldDefineConstants() {
        assertThat(SoundFontAssignment.MAX_BANK).isEqualTo(16383);
        assertThat(SoundFontAssignment.MAX_PROGRAM).isEqualTo(127);
    }
}
