package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecentProjectsStoreTest {

    private Preferences prefs;
    private RecentProjectsStore store;

    @BeforeEach
    void setUp() throws Exception {
        prefs = Preferences.userRoot().node("/com/benesquivelmusic/daw/test/recentprojects");
        prefs.clear();
        store = new RecentProjectsStore(prefs, 5);
    }

    @Test
    void shouldReturnEmptyListInitially() {
        assertThat(store.getRecentProjectPaths()).isEmpty();
    }

    @Test
    void shouldAddAndRetrieveProject() {
        Path path = Path.of("/tmp/project-a");
        store.addRecentProject(path);

        List<Path> recent = store.getRecentProjectPaths();
        assertThat(recent).hasSize(1);
        assertThat(recent.getFirst().toString()).endsWith("project-a");
    }

    @Test
    void shouldPlaceMostRecentFirst() {
        store.addRecentProject(Path.of("/tmp/project-a"));
        store.addRecentProject(Path.of("/tmp/project-b"));
        store.addRecentProject(Path.of("/tmp/project-c"));

        List<Path> recent = store.getRecentProjectPaths();
        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).toString()).endsWith("project-c");
        assertThat(recent.get(1).toString()).endsWith("project-b");
        assertThat(recent.get(2).toString()).endsWith("project-a");
    }

    @Test
    void shouldMoveExistingProjectToFront() {
        store.addRecentProject(Path.of("/tmp/project-a"));
        store.addRecentProject(Path.of("/tmp/project-b"));
        store.addRecentProject(Path.of("/tmp/project-c"));

        // Re-add project-a — should move to front
        store.addRecentProject(Path.of("/tmp/project-a"));

        List<Path> recent = store.getRecentProjectPaths();
        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).toString()).endsWith("project-a");
        assertThat(recent.get(1).toString()).endsWith("project-c");
        assertThat(recent.get(2).toString()).endsWith("project-b");
    }

    @Test
    void shouldEnforceMaxEntries() {
        for (int i = 0; i < 8; i++) {
            store.addRecentProject(Path.of("/tmp/project-" + i));
        }

        List<Path> recent = store.getRecentProjectPaths();
        assertThat(recent).hasSize(5);
        // Most recent should be project-7
        assertThat(recent.getFirst().toString()).endsWith("project-7");
    }

    @Test
    void shouldRemoveProject() {
        store.addRecentProject(Path.of("/tmp/project-a"));
        store.addRecentProject(Path.of("/tmp/project-b"));

        boolean removed = store.removeRecentProject(Path.of("/tmp/project-a"));

        assertThat(removed).isTrue();
        assertThat(store.getRecentProjectPaths()).hasSize(1);
        assertThat(store.getRecentProjectPaths().getFirst().toString()).endsWith("project-b");
    }

    @Test
    void shouldReturnFalseWhenRemovingNonexistentProject() {
        boolean removed = store.removeRecentProject(Path.of("/tmp/nonexistent"));
        assertThat(removed).isFalse();
    }

    @Test
    void shouldClearAllEntries() {
        store.addRecentProject(Path.of("/tmp/project-a"));
        store.addRecentProject(Path.of("/tmp/project-b"));

        store.clear();

        assertThat(store.getRecentProjectPaths()).isEmpty();
    }

    @Test
    void shouldPersistAcrossInstances() {
        store.addRecentProject(Path.of("/tmp/project-a"));
        store.addRecentProject(Path.of("/tmp/project-b"));

        // Create a new store using the same Preferences node
        RecentProjectsStore newStore = new RecentProjectsStore(prefs, 5);

        List<Path> recent = newStore.getRecentProjectPaths();
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).toString()).endsWith("project-b");
        assertThat(recent.get(1).toString()).endsWith("project-a");
    }

    @Test
    void shouldReturnMaxEntries() {
        assertThat(store.getMaxEntries()).isEqualTo(5);
    }

    @Test
    void shouldUseDefaultMaxEntries() {
        RecentProjectsStore defaultStore = new RecentProjectsStore(prefs);
        assertThat(defaultStore.getMaxEntries()).isEqualTo(RecentProjectsStore.DEFAULT_MAX_ENTRIES);
    }

    @Test
    void shouldRejectNullPreferences() {
        assertThatThrownBy(() -> new RecentProjectsStore(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectZeroMaxEntries() {
        assertThatThrownBy(() -> new RecentProjectsStore(prefs, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullPathOnAdd() {
        assertThatThrownBy(() -> store.addRecentProject(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPathOnRemove() {
        assertThatThrownBy(() -> store.removeRecentProject(null))
                .isInstanceOf(NullPointerException.class);
    }
}
