package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolbarStateStoreTest {

    private Preferences prefs;
    private ToolbarStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        prefs = Preferences.userRoot().node("toolbarStateTest_" + System.nanoTime());
        store = new ToolbarStateStore(prefs);
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    @Test
    void shouldRejectNullPreferences() {
        assertThatThrownBy(() -> new ToolbarStateStore(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Active view ──────────────────────────────────────────────────────────

    @Test
    void shouldDefaultToArrangementView() {
        assertThat(store.loadActiveView()).isEqualTo(DawView.ARRANGEMENT);
    }

    @Test
    void shouldPersistActiveView() {
        store.saveActiveView(DawView.MIXER);
        assertThat(store.loadActiveView()).isEqualTo(DawView.MIXER);
    }

    @Test
    void shouldPersistActiveViewAcrossInstances() {
        store.saveActiveView(DawView.EDITOR);

        ToolbarStateStore reloaded = new ToolbarStateStore(prefs);
        assertThat(reloaded.loadActiveView()).isEqualTo(DawView.EDITOR);
    }

    @Test
    void shouldFallBackToDefaultForInvalidActiveView() {
        prefs.put(ToolbarStateStore.KEY_ACTIVE_VIEW, "INVALID_VIEW");
        assertThat(store.loadActiveView()).isEqualTo(DawView.ARRANGEMENT);
    }

    @Test
    void shouldRejectNullActiveView() {
        assertThatThrownBy(() -> store.saveActiveView(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Edit tool ────────────────────────────────────────────────────────────

    @Test
    void shouldDefaultToPointerTool() {
        assertThat(store.loadEditTool()).isEqualTo(EditTool.POINTER);
    }

    @Test
    void shouldPersistEditTool() {
        store.saveEditTool(EditTool.PENCIL);
        assertThat(store.loadEditTool()).isEqualTo(EditTool.PENCIL);
    }

    @Test
    void shouldPersistEditToolAcrossInstances() {
        store.saveEditTool(EditTool.ERASER);

        ToolbarStateStore reloaded = new ToolbarStateStore(prefs);
        assertThat(reloaded.loadEditTool()).isEqualTo(EditTool.ERASER);
    }

    @Test
    void shouldFallBackToDefaultForInvalidEditTool() {
        prefs.put(ToolbarStateStore.KEY_EDIT_TOOL, "INVALID_TOOL");
        assertThat(store.loadEditTool()).isEqualTo(EditTool.POINTER);
    }

    @Test
    void shouldRejectNullEditTool() {
        assertThatThrownBy(() -> store.saveEditTool(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldPersistAllEditTools() {
        for (EditTool tool : EditTool.values()) {
            store.saveEditTool(tool);
            assertThat(store.loadEditTool()).isEqualTo(tool);
        }
    }

    // ── Snap enabled ─────────────────────────────────────────────────────────

    @Test
    void shouldDefaultToSnapEnabled() {
        assertThat(store.loadSnapEnabled()).isTrue();
    }

    @Test
    void shouldPersistSnapDisabled() {
        store.saveSnapEnabled(false);
        assertThat(store.loadSnapEnabled()).isFalse();
    }

    @Test
    void shouldPersistSnapEnabledAcrossInstances() {
        store.saveSnapEnabled(false);

        ToolbarStateStore reloaded = new ToolbarStateStore(prefs);
        assertThat(reloaded.loadSnapEnabled()).isFalse();
    }

    // ── Grid resolution ──────────────────────────────────────────────────────

    @Test
    void shouldDefaultToQuarterGrid() {
        assertThat(store.loadGridResolution()).isEqualTo(GridResolution.QUARTER);
    }

    @Test
    void shouldPersistGridResolution() {
        store.saveGridResolution(GridResolution.SIXTEENTH);
        assertThat(store.loadGridResolution()).isEqualTo(GridResolution.SIXTEENTH);
    }

    @Test
    void shouldPersistGridResolutionAcrossInstances() {
        store.saveGridResolution(GridResolution.EIGHTH_TRIPLET);

        ToolbarStateStore reloaded = new ToolbarStateStore(prefs);
        assertThat(reloaded.loadGridResolution()).isEqualTo(GridResolution.EIGHTH_TRIPLET);
    }

    @Test
    void shouldFallBackToDefaultForInvalidGridResolution() {
        prefs.put(ToolbarStateStore.KEY_GRID_RESOLUTION, "INVALID_RESOLUTION");
        assertThat(store.loadGridResolution()).isEqualTo(GridResolution.QUARTER);
    }

    @Test
    void shouldRejectNullGridResolution() {
        assertThatThrownBy(() -> store.saveGridResolution(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldPersistAllGridResolutions() {
        for (GridResolution resolution : GridResolution.values()) {
            store.saveGridResolution(resolution);
            assertThat(store.loadGridResolution()).isEqualTo(resolution);
        }
    }

    // ── Browser visible ──────────────────────────────────────────────────────

    @Test
    void shouldDefaultToBrowserHidden() {
        assertThat(store.loadBrowserVisible()).isFalse();
    }

    @Test
    void shouldPersistBrowserVisible() {
        store.saveBrowserVisible(true);
        assertThat(store.loadBrowserVisible()).isTrue();
    }

    @Test
    void shouldPersistBrowserVisibleAcrossInstances() {
        store.saveBrowserVisible(true);

        ToolbarStateStore reloaded = new ToolbarStateStore(prefs);
        assertThat(reloaded.loadBrowserVisible()).isTrue();
    }

    // ── All views persist correctly ──────────────────────────────────────────

    @Test
    void shouldPersistAllViews() {
        for (DawView view : DawView.values()) {
            store.saveActiveView(view);
            assertThat(store.loadActiveView()).isEqualTo(view);
        }
    }

    // ── Full round-trip ──────────────────────────────────────────────────────

    @Test
    void shouldPersistFullStateAcrossInstances() {
        store.saveActiveView(DawView.EDITOR);
        store.saveEditTool(EditTool.SCISSORS);
        store.saveSnapEnabled(false);
        store.saveGridResolution(GridResolution.THIRTY_SECOND);
        store.saveBrowserVisible(true);

        ToolbarStateStore reloaded = new ToolbarStateStore(prefs);
        assertThat(reloaded.loadActiveView()).isEqualTo(DawView.EDITOR);
        assertThat(reloaded.loadEditTool()).isEqualTo(EditTool.SCISSORS);
        assertThat(reloaded.loadSnapEnabled()).isFalse();
        assertThat(reloaded.loadGridResolution()).isEqualTo(GridResolution.THIRTY_SECOND);
        assertThat(reloaded.loadBrowserVisible()).isTrue();
    }

    // ── Corrupted state does not crash ───────────────────────────────────────

    @Test
    void shouldHandleAllCorruptedEnumValues() {
        prefs.put(ToolbarStateStore.KEY_ACTIVE_VIEW, "");
        prefs.put(ToolbarStateStore.KEY_EDIT_TOOL, "");
        prefs.put(ToolbarStateStore.KEY_GRID_RESOLUTION, "");

        assertThat(store.loadActiveView()).isEqualTo(DawView.ARRANGEMENT);
        assertThat(store.loadEditTool()).isEqualTo(EditTool.POINTER);
        assertThat(store.loadGridResolution()).isEqualTo(GridResolution.QUARTER);
    }
}
