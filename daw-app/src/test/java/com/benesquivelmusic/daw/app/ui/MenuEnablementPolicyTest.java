package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MenuEnablementPolicy}. The pure-logic mapping
 * from project state to menu enable/disable flags is tested in isolation
 * — no JavaFX toolkit required. These assertions were not possible
 * before {@code MenuEnablementPolicy} was extracted from
 * {@link DawMenuBarController} because the enable-state computation was
 * tangled with menu-item construction and JavaFX Platform startup.
 */
class MenuEnablementPolicyTest {

    @Test
    void cleanProjectWithNoSelectionDisablesEverything() {
        var enablement = MenuEnablementPolicy.compute(new MenuEnablementPolicy.MenuState(
                /* projectDirty */ false,
                /* canUndo     */ false,
                /* canRedo     */ false,
                /* hasClipboard*/ false,
                /* hasSelection*/ false,
                /* hasTracks   */ false));

        assertThat(enablement.saveDisabled()).isTrue();
        assertThat(enablement.exportSessionDisabled()).isTrue();
        assertThat(enablement.undoDisabled()).isTrue();
        assertThat(enablement.redoDisabled()).isTrue();
        assertThat(enablement.copyDisabled()).isTrue();
        assertThat(enablement.cutDisabled()).isTrue();
        assertThat(enablement.pasteDisabled()).isTrue();
        assertThat(enablement.duplicateDisabled()).isTrue();
        assertThat(enablement.deleteDisabled()).isTrue();
    }

    @Test
    void dirtyProjectEnablesSave() {
        var enablement = MenuEnablementPolicy.compute(new MenuEnablementPolicy.MenuState(
                true, false, false, false, false, false));
        assertThat(enablement.saveDisabled()).isFalse();
    }

    @Test
    void havingTracksEnablesExportSession() {
        var enablement = MenuEnablementPolicy.compute(new MenuEnablementPolicy.MenuState(
                false, false, false, false, false, true));
        assertThat(enablement.exportSessionDisabled()).isFalse();
    }

    @Test
    void undoRedoFlagsControlUndoRedoMenuItems() {
        var canUndoOnly = MenuEnablementPolicy.compute(new MenuEnablementPolicy.MenuState(
                false, true, false, false, false, false));
        assertThat(canUndoOnly.undoDisabled()).isFalse();
        assertThat(canUndoOnly.redoDisabled()).isTrue();

        var canRedoOnly = MenuEnablementPolicy.compute(new MenuEnablementPolicy.MenuState(
                false, false, true, false, false, false));
        assertThat(canRedoOnly.undoDisabled()).isTrue();
        assertThat(canRedoOnly.redoDisabled()).isFalse();
    }

    @Test
    void selectionEnablesCopyCutDuplicateDeleteButNotPaste() {
        var enablement = MenuEnablementPolicy.compute(new MenuEnablementPolicy.MenuState(
                false, false, false, /* hasClipboard */ false,
                /* hasSelection */ true, false));

        assertThat(enablement.copyDisabled()).isFalse();
        assertThat(enablement.cutDisabled()).isFalse();
        assertThat(enablement.duplicateDisabled()).isFalse();
        assertThat(enablement.deleteDisabled()).isFalse();
        // Paste depends on clipboard, not selection
        assertThat(enablement.pasteDisabled()).isTrue();
    }

    @Test
    void clipboardEnablesPasteRegardlessOfSelection() {
        var enablement = MenuEnablementPolicy.compute(new MenuEnablementPolicy.MenuState(
                false, false, false, /* hasClipboard */ true,
                /* hasSelection */ false, false));
        assertThat(enablement.pasteDisabled()).isFalse();
    }

    @Test
    void allEnabledStateMatchesAFullyLoadedSession() {
        var enablement = MenuEnablementPolicy.compute(new MenuEnablementPolicy.MenuState(
                true, true, true, true, true, true));

        assertThat(enablement.saveDisabled()).isFalse();
        assertThat(enablement.exportSessionDisabled()).isFalse();
        assertThat(enablement.undoDisabled()).isFalse();
        assertThat(enablement.redoDisabled()).isFalse();
        assertThat(enablement.copyDisabled()).isFalse();
        assertThat(enablement.cutDisabled()).isFalse();
        assertThat(enablement.pasteDisabled()).isFalse();
        assertThat(enablement.duplicateDisabled()).isFalse();
        assertThat(enablement.deleteDisabled()).isFalse();
    }
}
