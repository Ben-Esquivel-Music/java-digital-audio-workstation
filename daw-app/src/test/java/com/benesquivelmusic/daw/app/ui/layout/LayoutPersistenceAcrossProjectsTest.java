package com.benesquivelmusic.daw.app.ui.layout;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 282 acceptance criterion ({@code LayoutPersistenceAcrossProjectsTest}):
 * open project A with layout "Mixing", switch to project B with layout
 * "Tracking", switch back to A, assert layout is "Mixing".
 *
 * <p>The test stubs the per-project storage as an in-memory map keyed
 * by project name (in production this is the project file's {@code
 * layout} field — schema bump coordinates with story 188). On project
 * switch the controller (here, the test itself) calls
 * {@link LayoutManager#toJson()} to capture, then
 * {@link LayoutManager#fromJson(String)} on the next manager to
 * restore — the same round-trip the real project loader runs.</p>
 */
class LayoutPersistenceAcrossProjectsTest {

    private static final class RecordingHost implements LayoutManager.Host {
        String current = "{}";
        @Override public String captureDockLayoutJson() { return current; }
        @Override public void applyDockLayoutJson(String json) { current = json; }
    }

    @Test
    void layoutChoicePersistsPerProjectAcrossSwitches() {
        // In-memory "project file" store keyed by project id.
        Map<String, String> projectLayoutJsonByProject = new HashMap<>();

        // ── Open project A, switch to the "Mixing" built-in. ──────────
        RecordingHost hostA = new RecordingHost();
        LayoutManager mgrA = new LayoutManager(hostA);
        mgrA.fromJson(projectLayoutJsonByProject.get("A")); // null → defaults
        assertThat(mgrA.load(BuiltInLayouts.MIXING)).isTrue();
        // Persist A's layout state before switching projects.
        projectLayoutJsonByProject.put("A", mgrA.toJson());

        // ── Switch to project B, choose "Tracking". ───────────────────
        RecordingHost hostB = new RecordingHost();
        LayoutManager mgrB = new LayoutManager(hostB);
        mgrB.fromJson(projectLayoutJsonByProject.get("B"));
        assertThat(mgrB.load(BuiltInLayouts.TRACKING)).isTrue();
        projectLayoutJsonByProject.put("B", mgrB.toJson());

        // ── Switch back to project A. ─────────────────────────────────
        RecordingHost hostA2 = new RecordingHost();
        LayoutManager mgrA2 = new LayoutManager(hostA2);
        mgrA2.fromJson(projectLayoutJsonByProject.get("A"));

        assertThat(mgrA2.currentLayout()).isEqualTo(BuiltInLayouts.MIXING);
        // Verify the dock host was actually applied (not just the property).
        assertThat(hostA2.current)
                .isEqualTo(BuiltInLayouts.byName(BuiltInLayouts.MIXING).dockLayoutJson());
    }

    @Test
    void userSavedLayoutsRoundTripThroughJson() {
        RecordingHost host = new RecordingHost();
        LayoutManager mgr = new LayoutManager(host);
        host.current = "{\"entries\":[{\"id\":\"mixer\",\"zone\":\"CENTER\","
                + "\"tabIndex\":0,\"visible\":true}]}";
        mgr.saveCurrent("MyMix");

        String persisted = mgr.toJson();

        RecordingHost host2 = new RecordingHost();
        LayoutManager mgr2 = new LayoutManager(host2);
        mgr2.fromJson(persisted);

        assertThat(mgr2.findByName("MyMix")).isPresent();
        assertThat(mgr2.findByName("MyMix").get().dockLayoutJson())
                .isEqualTo(host.current);
        assertThat(mgr2.currentLayout()).isEqualTo("MyMix");
    }

    @Test
    void fromJsonNeverDuplicatesBuiltIns() {
        RecordingHost host = new RecordingHost();
        LayoutManager mgr = new LayoutManager(host);
        // Even if a malicious project file claims a built-in is user-saved,
        // the manager refuses to add a second entry with that name.
        mgr.fromJson("{\"current\":\"Default\",\"layouts\":["
                + "{\"name\":\"Default\",\"dock\":\"\"},"
                + "{\"name\":\"Custom\",\"dock\":\"\"}"
                + "]}");
        long defaults = mgr.savedLayouts().stream()
                .filter(l -> l.name().equals(BuiltInLayouts.DEFAULT))
                .count();
        assertThat(defaults).isEqualTo(1);
        assertThat(mgr.findByName("Custom")).isPresent();
    }

    @Test
    void fromJsonToleratesMalformedInput() {
        RecordingHost host = new RecordingHost();
        LayoutManager mgr = new LayoutManager(host);
        mgr.fromJson("not json at all {{{");
        // Built-ins still present; current resets to Default.
        assertThat(mgr.savedLayouts()).extracting(NamedLayout::name)
                .containsExactlyElementsOf(BuiltInLayouts.NAMES);
        assertThat(mgr.currentLayout()).isEqualTo(BuiltInLayouts.DEFAULT);
    }
}
