package com.benesquivelmusic.daw.core.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInPluginCategoryTest {

    @Test
    void shouldHaveFiveCategories() {
        assertThat(BuiltInPluginCategory.values()).hasSize(5);
    }

    @Test
    void shouldContainExpectedValues() {
        assertThat(BuiltInPluginCategory.values()).containsExactly(
                BuiltInPluginCategory.INSTRUMENT,
                BuiltInPluginCategory.EFFECT,
                BuiltInPluginCategory.ANALYZER,
                BuiltInPluginCategory.UTILITY,
                BuiltInPluginCategory.MASTERING
        );
    }

    @Test
    void shouldResolveFromName() {
        assertThat(BuiltInPluginCategory.valueOf("INSTRUMENT")).isEqualTo(BuiltInPluginCategory.INSTRUMENT);
        assertThat(BuiltInPluginCategory.valueOf("EFFECT")).isEqualTo(BuiltInPluginCategory.EFFECT);
        assertThat(BuiltInPluginCategory.valueOf("ANALYZER")).isEqualTo(BuiltInPluginCategory.ANALYZER);
        assertThat(BuiltInPluginCategory.valueOf("UTILITY")).isEqualTo(BuiltInPluginCategory.UTILITY);
        assertThat(BuiltInPluginCategory.valueOf("MASTERING")).isEqualTo(BuiltInPluginCategory.MASTERING);
    }

    @Test
    void shouldHaveExpectedDisplayNames() {
        assertThat(BuiltInPluginCategory.INSTRUMENT.displayName()).isEqualTo("Instruments");
        assertThat(BuiltInPluginCategory.EFFECT.displayName()).isEqualTo("Effects");
        assertThat(BuiltInPluginCategory.ANALYZER.displayName()).isEqualTo("Analyzers");
        assertThat(BuiltInPluginCategory.UTILITY.displayName()).isEqualTo("Utilities");
        assertThat(BuiltInPluginCategory.MASTERING.displayName()).isEqualTo("Mastering");
    }

    @Test
    void allCategoriesShouldHaveNonBlankDisplayName() {
        for (BuiltInPluginCategory category : BuiltInPluginCategory.values()) {
            assertThat(category.displayName())
                    .as("displayName for %s", category)
                    .isNotBlank();
        }
    }
}
