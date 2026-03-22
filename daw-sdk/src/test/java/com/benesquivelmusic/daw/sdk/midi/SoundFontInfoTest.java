package com.benesquivelmusic.daw.sdk.midi;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoundFontInfoTest {

    @Test
    void shouldCreateWithPresets() {
        List<SoundFontPreset> presets = List.of(
                new SoundFontPreset(0, 0, "Piano"),
                new SoundFontPreset(0, 1, "Bright Piano"));
        SoundFontInfo info = new SoundFontInfo(1, Path.of("/sounds/gm.sf2"), presets);

        assertThat(info.id()).isEqualTo(1);
        assertThat(info.path()).isEqualTo(Path.of("/sounds/gm.sf2"));
        assertThat(info.presets()).hasSize(2);
    }

    @Test
    void shouldCreateWithEmptyPresets() {
        SoundFontInfo info = new SoundFontInfo(0, Path.of("/test.sf2"), List.of());
        assertThat(info.presets()).isEmpty();
    }

    @Test
    void shouldCreateWithNullPresetsAsEmpty() {
        SoundFontInfo info = new SoundFontInfo(0, Path.of("/test.sf2"), null);
        assertThat(info.presets()).isEmpty();
    }

    @Test
    void shouldReturnUnmodifiablePresets() {
        List<SoundFontPreset> presets = List.of(new SoundFontPreset(0, 0, "Piano"));
        SoundFontInfo info = new SoundFontInfo(0, Path.of("/test.sf2"), presets);
        assertThatThrownBy(() -> info.presets().add(new SoundFontPreset(0, 1, "Other")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullPath() {
        assertThatThrownBy(() -> new SoundFontInfo(0, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    void shouldSupportRecordEquality() {
        Path path = Path.of("/test.sf2");
        List<SoundFontPreset> presets = List.of(new SoundFontPreset(0, 0, "Piano"));
        SoundFontInfo a = new SoundFontInfo(1, path, presets);
        SoundFontInfo b = new SoundFontInfo(1, path, presets);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
