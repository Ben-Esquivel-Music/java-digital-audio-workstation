package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import javafx.scene.input.KeyCombination;

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
        for (DawAction action : DawAction.values()) {
            assertThat(action.defaultBinding())
                    .as("defaultBinding for %s", action.name())
                    .isNotNull();
        }
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
