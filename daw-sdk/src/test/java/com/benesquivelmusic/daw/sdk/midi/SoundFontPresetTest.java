package com.benesquivelmusic.daw.sdk.midi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoundFontPresetTest {

    @Test
    void shouldCreatePreset() {
        SoundFontPreset preset = new SoundFontPreset(0, 0, "Acoustic Grand Piano");
        assertThat(preset.bank()).isZero();
        assertThat(preset.program()).isZero();
        assertThat(preset.name()).isEqualTo("Acoustic Grand Piano");
    }

    @Test
    void shouldAcceptBoundaryBankValues() {
        assertThat(new SoundFontPreset(0, 0, "Test").bank()).isZero();
        assertThat(new SoundFontPreset(16383, 0, "Test").bank()).isEqualTo(16383);
    }

    @Test
    void shouldAcceptBoundaryProgramValues() {
        assertThat(new SoundFontPreset(0, 0, "Test").program()).isZero();
        assertThat(new SoundFontPreset(0, 127, "Test").program()).isEqualTo(127);
    }

    @Test
    void shouldRejectNegativeBank() {
        assertThatThrownBy(() -> new SoundFontPreset(-1, 0, "Test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank");
    }

    @Test
    void shouldRejectBankAboveMax() {
        assertThatThrownBy(() -> new SoundFontPreset(16384, 0, "Test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank");
    }

    @Test
    void shouldRejectNegativeProgram() {
        assertThatThrownBy(() -> new SoundFontPreset(0, -1, "Test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("program");
    }

    @Test
    void shouldRejectProgramAboveMax() {
        assertThatThrownBy(() -> new SoundFontPreset(0, 128, "Test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("program");
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new SoundFontPreset(0, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> new SoundFontPreset(0, 0, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void shouldDefineConstants() {
        assertThat(SoundFontPreset.MAX_BANK).isEqualTo(16383);
        assertThat(SoundFontPreset.MAX_PROGRAM).isEqualTo(127);
    }

    @Test
    void shouldSupportRecordEquality() {
        SoundFontPreset a = new SoundFontPreset(0, 0, "Piano");
        SoundFontPreset b = new SoundFontPreset(0, 0, "Piano");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
