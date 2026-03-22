package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectMetadataTest {

    @Test
    void shouldCreateNewMetadata() {
        ProjectMetadata metadata = ProjectMetadata.createNew("My Song");

        assertThat(metadata.name()).isEqualTo("My Song");
        assertThat(metadata.createdAt()).isNotNull();
        assertThat(metadata.lastModified()).isNotNull();
        assertThat(metadata.projectPath()).isNull();
    }

    @Test
    void shouldTouchUpdateLastModified() throws InterruptedException {
        ProjectMetadata metadata = ProjectMetadata.createNew("Test");
        Instant before = metadata.lastModified();

        Thread.sleep(10);
        ProjectMetadata touched = metadata.touch();

        assertThat(touched.lastModified()).isAfter(before);
        assertThat(touched.name()).isEqualTo("Test");
        assertThat(touched.createdAt()).isEqualTo(metadata.createdAt());
    }

    @Test
    void shouldSetPath() {
        ProjectMetadata metadata = ProjectMetadata.createNew("Test");
        ProjectMetadata withPath = metadata.withPath(Path.of("/tmp/project"));

        assertThat(withPath.projectPath()).isEqualTo(Path.of("/tmp/project"));
        assertThat(withPath.name()).isEqualTo("Test");
    }

    @Test
    void shouldSetName() {
        ProjectMetadata metadata = ProjectMetadata.createNew("Original");
        ProjectMetadata renamed = metadata.withName("Renamed");

        assertThat(renamed.name()).isEqualTo("Renamed");
        assertThat(renamed.createdAt()).isEqualTo(metadata.createdAt());
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> ProjectMetadata.createNew(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> new ProjectMetadata("  ", Instant.now(), Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullCreatedAt() {
        assertThatThrownBy(() -> new ProjectMetadata("Test", null, Instant.now(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullLastModified() {
        assertThatThrownBy(() -> new ProjectMetadata("Test", Instant.now(), null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
