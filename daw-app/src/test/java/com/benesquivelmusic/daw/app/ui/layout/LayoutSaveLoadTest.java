package com.benesquivelmusic.daw.app.ui.layout;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip save → rearrange → load test for {@link LayoutManager}.
 *
 * <p>Story 282 acceptance criterion ({@code LayoutSaveLoadTest}):
 * arrange three panels (arrangement centre, mixer right, browser left),
 * save as "Test"; rearrange to defaults; load "Test"; assert the
 * previous arrangement is restored.</p>
 *
 * <p>The test uses a recording {@link LayoutManager.Host} stub so it
 * runs headlessly without JavaFX threads or a screen.</p>
 */
class LayoutSaveLoadTest {

    /** Recording host: stores whatever JSON the manager hands it. */
    private static final class RecordingHost implements LayoutManager.Host {
        String current = "<initial>";
        List<String> applied = new ArrayList<>();

        @Override public String captureDockLayoutJson() { return current; }

        @Override public void applyDockLayoutJson(String json) {
            applied.add(json);
            current = json;
        }
    }

    @Test
    void saveCurrentThenLoadRestoresLayout() {
        RecordingHost host = new RecordingHost();
        LayoutManager mgr = new LayoutManager(host);

        // "Arrange three panels (arrangement centre, mixer right, browser
        // left), save as Test". We don't need a real DockLayout JSON for
        // the manager to do its work — any opaque string round-trips.
        host.current = "{\"entries\":[{\"id\":\"arrangement\",\"zone\":\"CENTER\","
                + "\"tabIndex\":0,\"visible\":true},{\"id\":\"mixer\",\"zone\":\"RIGHT\","
                + "\"tabIndex\":0,\"visible\":true},{\"id\":\"browser\",\"zone\":\"LEFT\","
                + "\"tabIndex\":0,\"visible\":true}]}";
        NamedLayout saved = mgr.saveCurrent("Test");
        String testJson = host.current;
        assertThat(saved.builtIn()).isFalse();
        assertThat(mgr.savedLayouts()).extracting(NamedLayout::name)
                .contains("Test");
        assertThat(mgr.currentLayout()).isEqualTo("Test");

        // Rearrange to defaults (simulated by overwriting the host's current).
        host.current = "{\"entries\":[]}";

        // Load "Test"; assert the previous arrangement is restored.
        boolean loaded = mgr.load("Test");
        assertThat(loaded).isTrue();
        assertThat(host.applied).last().isEqualTo(testJson);
        assertThat(mgr.currentLayout()).isEqualTo("Test");
    }

    @Test
    void saveCurrentRefusesToOverwriteBuiltIn() {
        RecordingHost host = new RecordingHost();
        LayoutManager mgr = new LayoutManager(host);

        host.current = "{\"entries\":[{\"id\":\"x\",\"zone\":\"CENTER\","
                + "\"tabIndex\":0,\"visible\":true}]}";
        // "Default" is a built-in; saveCurrent must leave it untouched.
        NamedLayout retained = mgr.saveCurrent(BuiltInLayouts.DEFAULT);
        assertThat(retained.builtIn()).isTrue();
        assertThat(retained.dockLayoutJson())
                .isNotEqualTo(host.current); // unchanged
    }

    @Test
    void deleteAndRenameRespectBuiltIns() {
        RecordingHost host = new RecordingHost();
        LayoutManager mgr = new LayoutManager(host);

        assertThat(mgr.delete(BuiltInLayouts.LIVE)).isFalse();
        assertThat(mgr.rename(BuiltInLayouts.MIXING, "Mixing2")).isFalse();

        host.current = "{\"entries\":[]}";
        mgr.saveCurrent("UserA");
        assertThat(mgr.rename("UserA", "UserB")).isTrue();
        assertThat(mgr.findByName("UserA")).isEmpty();
        assertThat(mgr.findByName("UserB")).isPresent();
        assertThat(mgr.delete("UserB")).isTrue();
        assertThat(mgr.findByName("UserB")).isEmpty();
    }

    @Test
    void renameRejectsBlankOrCollidingNames() {
        RecordingHost host = new RecordingHost();
        LayoutManager mgr = new LayoutManager(host);
        host.current = "{}";
        mgr.saveCurrent("A");
        mgr.saveCurrent("B");
        assertThat(mgr.rename("A", "")).isFalse();
        assertThat(mgr.rename("A", "   ")).isFalse();
        assertThat(mgr.rename("A", "B")).isFalse();           // collision
        assertThat(mgr.rename("A", BuiltInLayouts.LIVE)).isFalse(); // collides with built-in
    }
}
