package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.sdk.editor.PluginCategory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 303 — pins the §4.4 icon-resolution contract of {@link PluginIcons}: a
 * declared {@code iconHint} maps into the host icon pack and WINS over the
 * category default; an unknown hint (or none) falls back to the category glyph.
 * Pure mapping test, no JavaFX scene needed.
 */
class PluginIconsResolveTest {

    @Test
    void nonBlankIconHintWinsOverCategoryDefault() {
        assertThat(PluginIcons.resolve(PluginCategory.REVERB_AND_DELAY, "delay"))
                .as("a known hint overrides the category default (REVERB)")
                .isEqualTo(DawIcon.DELAY);
    }

    @Test
    void iconHintIsNormalisedLikeManifestHints() {
        assertThat(PluginIcons.resolve(PluginCategory.DYNAMICS, "low-pass"))
                .as("hints normalise trim/case/dashes into DawIcon constant names")
                .isEqualTo(DawIcon.LOW_PASS);
        assertThat(PluginIcons.resolve(PluginCategory.DYNAMICS, "  Compressor "))
                .isEqualTo(DawIcon.COMPRESSOR);
    }

    @Test
    void unknownOrMissingHintFallsBackToCategoryGlyph() {
        assertThat(PluginIcons.resolve(PluginCategory.REVERB_AND_DELAY, "no-such-glyph"))
                .as("an unresolvable hint falls back to the category default")
                .isEqualTo(DawIcon.REVERB);
        assertThat(PluginIcons.resolve(PluginCategory.UTILITY, null))
                .isEqualTo(DawIcon.KNOB);
        assertThat(PluginIcons.resolve(PluginCategory.SPATIAL, ""))
                .isEqualTo(DawIcon.CORRELATION);
    }
}
