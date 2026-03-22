package com.benesquivelmusic.daw.sdk.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalPluginFormatTest {

    @Test
    void shouldContainClapAndLv2() {
        assertThat(ExternalPluginFormat.values())
                .containsExactly(ExternalPluginFormat.CLAP, ExternalPluginFormat.LV2);
    }

    @Test
    void shouldResolveFromName() {
        assertThat(ExternalPluginFormat.valueOf("CLAP")).isEqualTo(ExternalPluginFormat.CLAP);
        assertThat(ExternalPluginFormat.valueOf("LV2")).isEqualTo(ExternalPluginFormat.LV2);
    }
}
