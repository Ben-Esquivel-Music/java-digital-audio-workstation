package com.benesquivelmusic.daw.core.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInPluginCategoryTest {

    @Test
    void shouldHaveFourCategories() {
        assertThat(BuiltInPluginCategory.values()).hasSize(4);
    }

    @Test
    void shouldContainExpectedValues() {
        assertThat(BuiltInPluginCategory.values()).containsExactly(
                BuiltInPluginCategory.INSTRUMENT,
                BuiltInPluginCategory.EFFECT,
                BuiltInPluginCategory.ANALYZER,
                BuiltInPluginCategory.UTILITY
        );
    }

    @Test
    void shouldResolveFromName() {
        assertThat(BuiltInPluginCategory.valueOf("INSTRUMENT")).isEqualTo(BuiltInPluginCategory.INSTRUMENT);
        assertThat(BuiltInPluginCategory.valueOf("EFFECT")).isEqualTo(BuiltInPluginCategory.EFFECT);
        assertThat(BuiltInPluginCategory.valueOf("ANALYZER")).isEqualTo(BuiltInPluginCategory.ANALYZER);
        assertThat(BuiltInPluginCategory.valueOf("UTILITY")).isEqualTo(BuiltInPluginCategory.UTILITY);
    }
}
