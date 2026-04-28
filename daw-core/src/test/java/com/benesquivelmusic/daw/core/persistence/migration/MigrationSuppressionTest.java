package com.benesquivelmusic.daw.core.persistence.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationSuppressionTest {

    @Test
    void initiallyNotSuppressed(@org.junit.jupiter.api.io.TempDir Path projectDir) {
        assertThat(MigrationSuppression.isSuppressed(projectDir, 5)).isFalse();
    }

    @Test
    void suppressionIsRecordedAndQueryable(@org.junit.jupiter.api.io.TempDir Path projectDir) {
        MigrationSuppression.suppress(projectDir, 7);

        assertThat(MigrationSuppression.isSuppressed(projectDir, 7)).isTrue();
        // Suppression at a recorded version also covers older targets — a
        // re-load of the same project at the same/earlier version is silent.
        assertThat(MigrationSuppression.isSuppressed(projectDir, 5)).isTrue();
    }

    @Test
    void suppressionDoesNotApplyToFutureSchemaVersions(
            @org.junit.jupiter.api.io.TempDir Path projectDir) {
        MigrationSuppression.suppress(projectDir, 3);

        // A future schema bump produces a new toVersion that wasn't
        // covered by the user's earlier "don't show again" choice — the
        // dialog should re-appear so they can review the new changes.
        assertThat(MigrationSuppression.isSuppressed(projectDir, 4)).isFalse();
    }

    @Test
    void clearRemovesMarker(@org.junit.jupiter.api.io.TempDir Path projectDir) throws Exception {
        MigrationSuppression.suppress(projectDir, 2);
        MigrationSuppression.clear(projectDir);

        assertThat(MigrationSuppression.isSuppressed(projectDir, 2)).isFalse();
        assertThat(Files.exists(projectDir.resolve(MigrationSuppression.MARKER_FILENAME)))
                .isFalse();
    }

    @Test
    void corruptMarkerIsTreatedAsNotSuppressed(
            @org.junit.jupiter.api.io.TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve(MigrationSuppression.MARKER_FILENAME), "not-a-number");

        assertThat(MigrationSuppression.isSuppressed(projectDir, 2)).isFalse();
    }

    @Test
    void nullProjectDirectoryIsNeverSuppressed() {
        assertThat(MigrationSuppression.isSuppressed(null, 1)).isFalse();
    }

    @Test
    void suppressNullProjectThrows() {
        assertThatThrownBy(() -> MigrationSuppression.suppress(null, 1))
                .isInstanceOf(NullPointerException.class);
    }
}
