package com.benesquivelmusic.daw.app.ui;

import javafx.scene.input.KeyCombination;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DawActionTest {

    @Test
    void everyActionShouldHaveDisplayName() {
        for (DawAction action : DawAction.values()) {
            assertThat(action.displayName())
                    .as("displayName for %s", action.name())
                    .isNotNull()
                    .isNotBlank();
        }
    }

    @Test
    void everyActionShouldHaveCategory() {
        for (DawAction action : DawAction.values()) {
            assertThat(action.category())
                    .as("category for %s", action.name())
                    .isNotNull();
        }
    }

    @Test
    void everyCategoryShouldHaveDisplayName() {
        for (DawAction.Category category : DawAction.Category.values()) {
            assertThat(category.displayName())
                    .as("displayName for category %s", category.name())
                    .isNotNull()
                    .isNotBlank();
        }
    }

    @Test
    void allActionsShouldHaveDefaultBindings() {
        // Story 134 introduced TOGGLE_PRE_ROLL / TOGGLE_POST_ROLL — these
        // are surfaced primarily as toolbar toggle buttons and have no
        // factory keyboard shortcut. The DawAction javadoc explicitly
        // permits a {@code null} default for such actions.
        java.util.Set<DawAction> nullBindingAllowed = java.util.Set.of(
                DawAction.TOGGLE_PRE_ROLL, DawAction.TOGGLE_POST_ROLL,
                DawAction.TIME_STRETCH_CLIP, DawAction.PITCH_SHIFT_CLIP,
                DawAction.OPEN_IMMERSIVE_AB,
                DawAction.EXPORT_RENDER_QUEUE,
                // Story 189 — File-menu only, no keyboard accelerator.
                DawAction.ARCHIVE_PROJECT, DawAction.RESTORE_FROM_ARCHIVE,
                // Story 329 — File-menu / Command Palette only, no keyboard accelerator.
                DawAction.RELINK_MISSING_ASSETS,
                // Sessions Stage 3 — Window-menu only, no keyboard accelerator.
                DawAction.TOGGLE_SESSION_MANAGER,
                // Story 333 (ruling R12 #5) — File ▸ Save As… and the palette; Shortcut+Shift+S is Toggle Snap.
                DawAction.SAVE_AS);
        for (DawAction action : DawAction.values()) {
            if (nullBindingAllowed.contains(action)) {
                continue;
            }
            assertThat(action.defaultBinding())
                    .as("defaultBinding for %s", action.name())
                    .isNotNull();
        }
    }

    @Test
    void saveProjectAsIsAnEditingActionWithNoDefaultShortcutNamedApartFromTheWorkspacesSaveAs() {
        // Story 333 — ruling R12 #5 (no shortcut in this story) and ENG-N8 (the palette names it apart).
        assertThat(DawAction.SAVE_AS.displayName()).isEqualTo("Save Project As…");
        assertThat(DawAction.SAVE_AS.category()).isEqualTo(DawAction.Category.EDITING);
        assertThat(DawAction.SAVE_AS.defaultBinding()).isNull();
        assertThat(DawAction.SAVE_AS.displayName()).isNotEqualTo(DawAction.WORKSPACE_SAVE_AS.displayName());
    }

    @Test
    void closeProjectIsShortcutShiftWAndExitIsShortcutQWithExitBesideSettingsAndThePalette() {
        // Story 333 — ruling R12 #5's shortcuts; design §3.9: Exit sits with Settings and Command Palette.
        assertThat(DawAction.CLOSE_PROJECT.displayName()).isEqualTo("Close Project");
        assertThat(DawAction.CLOSE_PROJECT.category()).isEqualTo(DawAction.Category.EDITING);
        assertThat(DawAction.CLOSE_PROJECT.defaultBinding()).isEqualTo(new javafx.scene.input.KeyCodeCombination(
                javafx.scene.input.KeyCode.W, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        assertThat(DawAction.EXIT.displayName()).isEqualTo("Exit");
        assertThat(DawAction.EXIT.category()).isEqualTo(DawAction.Category.APPLICATION);
        assertThat(DawAction.EXIT.defaultBinding()).isEqualTo(new javafx.scene.input.KeyCodeCombination(
                javafx.scene.input.KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
    }

    @Test
    void playStopShouldBeTransportCategory() {
        assertThat(DawAction.PLAY_STOP.category()).isEqualTo(DawAction.Category.TRANSPORT);
    }

    @Test
    void undoShouldBeEditingCategory() {
        assertThat(DawAction.UNDO.category()).isEqualTo(DawAction.Category.EDITING);
    }

    @Test
    void addAudioTrackShouldBeTracksCategory() {
        assertThat(DawAction.ADD_AUDIO_TRACK.category()).isEqualTo(DawAction.Category.TRACKS);
    }

    @Test
    void toolPointerShouldBeToolsCategory() {
        assertThat(DawAction.TOOL_POINTER.category()).isEqualTo(DawAction.Category.TOOLS);
    }

    @Test
    void zoomInShouldBeNavigationCategory() {
        assertThat(DawAction.ZOOM_IN.category()).isEqualTo(DawAction.Category.NAVIGATION);
    }

    @Test
    void viewArrangementShouldBeViewsCategory() {
        assertThat(DawAction.VIEW_ARRANGEMENT.category()).isEqualTo(DawAction.Category.VIEWS);
    }

    @Test
    void openSettingsShouldBeApplicationCategory() {
        assertThat(DawAction.OPEN_SETTINGS.category()).isEqualTo(DawAction.Category.APPLICATION);
    }

    @Test
    void playStopDefaultShouldBeSpace() {
        KeyCombination binding = DawAction.PLAY_STOP.defaultBinding();
        assertThat(binding).isNotNull();
        assertThat(binding.getName()).contains("Space");
    }

    @Test
    void saveDefaultShouldContainShortcutAndS() {
        KeyCombination binding = DawAction.SAVE.defaultBinding();
        assertThat(binding).isNotNull();
        assertThat(binding.getName()).contains("S");
    }

    @Test
    void defaultBindingsShouldBeUniqueAcrossActions() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DawAction action : DawAction.values()) {
            KeyCombination binding = action.defaultBinding();
            if (binding != null) {
                String name = binding.getName();
                assertThat(seen.add(name))
                        .as("Duplicate default binding '%s' for action %s", name, action.name())
                        .isTrue();
            }
        }
    }

    @Test
    void categoryShouldCoverExpectedNames() {
        assertThat(DawAction.Category.TRANSPORT.displayName()).isEqualTo("Transport");
        assertThat(DawAction.Category.EDITING.displayName()).isEqualTo("Editing");
        assertThat(DawAction.Category.TRACKS.displayName()).isEqualTo("Tracks");
        assertThat(DawAction.Category.TOOLS.displayName()).isEqualTo("Tools");
        assertThat(DawAction.Category.NAVIGATION.displayName()).isEqualTo("Navigation");
        assertThat(DawAction.Category.VIEWS.displayName()).isEqualTo("Views");
        assertThat(DawAction.Category.APPLICATION.displayName()).isEqualTo("Application");
    }
}
