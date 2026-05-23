package com.benesquivelmusic.daw.app.ui.layout;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 282 acceptance criterion ({@code LayoutMenuTest}): open
 * View → Layout, assert the five built-in layouts appear in the menu
 * and the current layout is checked.
 *
 * <p>This test pins the data-model contract that the View → Layout menu
 * binds to — the menu itself is a thin FX consumer of {@link
 * LayoutManager#savedLayouts()} and {@link LayoutManager#currentLayoutProperty()},
 * so the headless test below covers the radio-check requirement without
 * needing to spin up a {@code Stage}.</p>
 */
class LayoutMenuTest {

    private static final class StubHost implements LayoutManager.Host {
        String current = "{}";
        @Override public String captureDockLayoutJson() { return current; }
        @Override public void applyDockLayoutJson(String json) { current = json; }
    }

    @Test
    void fiveBuiltInLayoutsAppearInCanonicalOrder() {
        LayoutManager mgr = new LayoutManager(new StubHost());
        // The first five entries of savedLayouts() drive the menu's radio
        // group; they must be the built-ins in the documented order.
        assertThat(mgr.savedLayouts().subList(0, BuiltInLayouts.NAMES.size()))
                .extracting(NamedLayout::name)
                .containsExactly(
                        BuiltInLayouts.DEFAULT,
                        BuiltInLayouts.TRACKING,
                        BuiltInLayouts.MIXING,
                        BuiltInLayouts.MASTERING,
                        BuiltInLayouts.LIVE);
        // All five are marked read-only / built-in.
        assertThat(mgr.savedLayouts().subList(0, BuiltInLayouts.NAMES.size()))
                .allMatch(NamedLayout::builtIn);
    }

    @Test
    void currentLayoutPropertyDrivesRadioCheck() {
        LayoutManager mgr = new LayoutManager(new StubHost());
        // Fresh manager: the "Default" radio is the one checked.
        assertThat(mgr.currentLayoutProperty().get())
                .isEqualTo(BuiltInLayouts.DEFAULT);

        // Loading a different built-in flips the current property — the
        // FX menu's listener swaps the radio check accordingly.
        boolean loaded = mgr.load(BuiltInLayouts.MIXING);
        assertThat(loaded).isTrue();
        assertThat(mgr.currentLayoutProperty().get())
                .isEqualTo(BuiltInLayouts.MIXING);

        // A user-saved layout appended via "Save Layout As…" appears
        // after the built-ins.
        mgr.saveCurrent("My Custom");
        assertThat(mgr.savedLayouts())
                .extracting(NamedLayout::name)
                .endsWith("My Custom");
        assertThat(mgr.currentLayoutProperty().get())
                .isEqualTo("My Custom");
    }

    @Test
    void builtInsRegistryExposesAllFiveNames() {
        // The Messages.properties key set for the View → Layout menu
        // labels is derived from this list, so it is pinned here.
        assertThat(BuiltInLayouts.NAMES).containsExactly(
                "Default", "Tracking", "Mixing", "Mastering", "Live");
        assertThat(BuiltInLayouts.all()).hasSize(5).allMatch(NamedLayout::builtIn);
    }
}
