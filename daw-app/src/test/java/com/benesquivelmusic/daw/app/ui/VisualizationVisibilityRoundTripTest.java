package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dock.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;
import static org.assertj.core.api.Assertions.assertThat;

class VisualizationVisibilityRoundTripTest {
    @TempDir Path temporary;
    private static final List<String> PANELS = List.of(DefaultWorkspaces.PANEL_SPECTRUM,
            DefaultWorkspaces.PANEL_LEVELS, DefaultWorkspaces.PANEL_WAVEFORM,
            DefaultWorkspaces.PANEL_CORRELATION, DefaultWorkspaces.PANEL_LOUDNESS, DefaultWorkspaces.PANEL_TUNER);

    @Test
    void togglesAndLegacyRowMigrationSurviveASeedIntoTheNextStartup() throws Exception {
        Preferences backing = Preferences.userRoot().node("story319-" + System.nanoTime());
        try {
            var preferences = new VisualizationPreferences(backing);
            preferences.setRowVisible(false);
            DockManager first = manager("first");
            preferences.restore(first);
            for (String id : PANELS) assertThat(first.layout().entry(id).orElseThrow().visible()).isFalse();
            first.setVisible(DefaultWorkspaces.PANEL_SPECTRUM, true);
            first.setVisible(DefaultWorkspaces.PANEL_TUNER, true);
            preferences.saveLayout(first.layout());
            DockManager restored = manager("restored");
            new VisualizationPreferences(backing).restore(restored);
            for (String id : PANELS) assertThat(restored.layout().entry(id).orElseThrow().visible())
                    .isEqualTo(first.layout().entry(id).orElseThrow().visible());
            assertThat(restored.layout().entry(DefaultWorkspaces.PANEL_TUNER).orElseThrow().visible()).isTrue();
            assertThat(restored.layout().entry(DefaultWorkspaces.PANEL_SPECTRUM).orElseThrow().visible()).isTrue();
        } finally { backing.removeNode(); }
    }

    private DockManager manager(String name) {
        var manager = new DockManager(_ -> { }, new FloatingWindowStore(temporary.resolve(name + ".json")));
        for (String id : PANELS) manager.register(new Dockable() {
            public String dockId() { return id; }
            public String displayName() { return id; }
            public DockZone preferredZone() { return DockZone.BOTTOM; }
        });
        return manager;
    }
}
