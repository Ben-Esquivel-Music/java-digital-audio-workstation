package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin.MenuEntry;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginCategory;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin;
import com.benesquivelmusic.daw.core.plugin.ParametricEqPlugin;
import com.benesquivelmusic.daw.core.plugin.SpectrumAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.VirtualKeyboardPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the pure, non-FX picker filter
 * {@link InnerChainPane#effectMenuEntries(List)} — the only daw-core-testable
 * seam of the story 302 §8.3 Mid/Side editor port. No JavaFX toolkit is
 * started and no controls are constructed; the FX-level behavioural tests
 * live in daw-app.
 */
class InnerChainPaneTest {

    private static final MenuEntry EQ = new MenuEntry(
            ParametricEqPlugin.class, "Parametric EQ", "eq", BuiltInPluginCategory.EFFECT);
    private static final MenuEntry WRAPPER = new MenuEntry(
            MidSideWrapperPlugin.class, "Mid/Side Wrapper", "stereo", BuiltInPluginCategory.EFFECT);
    private static final MenuEntry KEYBOARD = new MenuEntry(
            VirtualKeyboardPlugin.class, "Virtual Keyboard", "keyboard",
            BuiltInPluginCategory.INSTRUMENT);
    private static final MenuEntry ANALYZER = new MenuEntry(
            SpectrumAnalyzerPlugin.class, "Spectrum Analyzer", "spectrum",
            BuiltInPluginCategory.ANALYZER);

    @Test
    void keepsOnlyEffectCategoryEntries() {
        List<MenuEntry> filtered =
                InnerChainPane.effectMenuEntries(List.of(EQ, KEYBOARD, ANALYZER));
        assertThat(filtered).containsExactly(EQ);
    }

    @Test
    void excludesTheWrapperItselfSoChainsCannotNest() {
        List<MenuEntry> filtered =
                InnerChainPane.effectMenuEntries(List.of(WRAPPER, EQ));
        assertThat(filtered).containsExactly(EQ);
    }

    @Test
    void emptyInputYieldsEmptyOutput() {
        assertThat(InnerChainPane.effectMenuEntries(List.of())).isEmpty();
    }

    @Test
    void rejectsNullEntries() {
        assertThatThrownBy(() -> InnerChainPane.effectMenuEntries(null))
                .isInstanceOf(NullPointerException.class);
    }
}
