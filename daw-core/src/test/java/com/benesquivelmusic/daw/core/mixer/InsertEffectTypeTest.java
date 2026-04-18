package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InsertEffectTypeTest {

    @Test
    void shouldHaveTenTypes() {
        assertThat(InsertEffectType.values()).hasSize(21);
    }

    @Test
    void shouldProvideDisplayNames() {
        assertThat(InsertEffectType.PARAMETRIC_EQ.getDisplayName()).isEqualTo("Parametric EQ");
        assertThat(InsertEffectType.COMPRESSOR.getDisplayName()).isEqualTo("Compressor");
        assertThat(InsertEffectType.LIMITER.getDisplayName()).isEqualTo("Limiter");
        assertThat(InsertEffectType.REVERB.getDisplayName()).isEqualTo("Reverb");
        assertThat(InsertEffectType.DELAY.getDisplayName()).isEqualTo("Delay");
        assertThat(InsertEffectType.CHORUS.getDisplayName()).isEqualTo("Chorus");
        assertThat(InsertEffectType.NOISE_GATE.getDisplayName()).isEqualTo("Noise Gate");
        assertThat(InsertEffectType.STEREO_IMAGER.getDisplayName()).isEqualTo("Stereo Imager");
        assertThat(InsertEffectType.GRAPHIC_EQ.getDisplayName()).isEqualTo("Graphic EQ");
        assertThat(InsertEffectType.CLAP_PLUGIN.getDisplayName()).isEqualTo("CLAP Plugin");
    }

    @Test
    void shouldResolveFromName() {
        assertThat(InsertEffectType.valueOf("COMPRESSOR")).isEqualTo(InsertEffectType.COMPRESSOR);
        assertThat(InsertEffectType.valueOf("GRAPHIC_EQ")).isEqualTo(InsertEffectType.GRAPHIC_EQ);
    }
}
