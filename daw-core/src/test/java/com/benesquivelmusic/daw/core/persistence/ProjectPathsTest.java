package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one write/read rule shared by {@code ProjectSerializer} and
 * {@code ProjectDeserializer} (story 323; Persistence book §3.3).
 */
class ProjectPathsTest {

    private static final String UNPARSEABLE = "bad\u0000name.wav";

    @TempDir
    Path root;

    @Test
    void relativizeWritesAnAbsolutePathUnderTheRootRelativeWithForwardSlashes() {
        String absolute = root.resolve("audio").resolve("takes").resolve("x.wav").toString();

        assertThat(ProjectPaths.relativize(root, absolute)).isEqualTo("audio/takes/x.wav");
    }

    @Test
    void relativizeNormalisesDotDotSegmentsBeforeDecidingContainment() {
        String wandering = root.resolve("audio").resolve("..").resolve("audio").resolve("x.wav").toString();

        assertThat(ProjectPaths.relativize(root, wandering)).isEqualTo("audio/x.wav");
    }

    @Test
    void relativizeKeepsEverythingElseVerbatim() {
        String outside = root.resolveSibling("elsewhere").resolve("x.wav").toString();

        assertThat(ProjectPaths.relativize(null, root.resolve("x.wav").toString()))
                .as("no root: nothing to relativise against")
                .isEqualTo(root.resolve("x.wav").toString());
        assertThat(ProjectPaths.relativize(root, "audio/x.wav")).isEqualTo("audio/x.wav");
        assertThat(ProjectPaths.relativize(root, outside)).isEqualTo(outside);
        assertThat(ProjectPaths.relativize(root, root.toString()))
                .as("the root itself is not a file reference")
                .isEqualTo(root.toString());
        assertThat(ProjectPaths.relativize(root, UNPARSEABLE)).isEqualTo(UNPARSEABLE);
        assertThat(ProjectPaths.relativize(root, "")).isEmpty();
        assertThat(ProjectPaths.relativize(root, null)).isNull();
    }

    @Test
    void resolveRebasesAProjectRelativeReferenceToAnAbsoluteNormalisedPath() {
        String expected = root.toAbsolutePath().normalize().resolve("audio").resolve("x.wav").toString();

        assertThat(ProjectPaths.resolve(root, "audio/x.wav")).isEqualTo(expected);
        assertThat(ProjectPaths.resolve(root, "audio/../audio/x.wav")).isEqualTo(expected);
    }

    @Test
    void resolveKeepsRootedUnparseableAndBaselessReferencesVerbatim() {
        String absolute = root.resolve("x.wav").toString();

        assertThat(ProjectPaths.resolve(null, "audio/x.wav")).isEqualTo("audio/x.wav");
        assertThat(ProjectPaths.resolve(root, absolute)).isEqualTo(absolute);
        assertThat(ProjectPaths.resolve(root, "/audio/take1.wav"))
                .as("a leading-slash reference has a root component on Windows and on POSIX alike, so it is kept verbatim")
                .isEqualTo("/audio/take1.wav");
        assertThat(ProjectPaths.resolve(root, UNPARSEABLE)).isEqualTo(UNPARSEABLE);
        assertThat(ProjectPaths.resolve(root, "")).isEmpty();
        assertThat(ProjectPaths.resolve(root, null)).isNull();
    }

    @Test
    void relativizeThenResolveRoundTripsToTheNormalisedAbsolutePath() {
        Path file = root.resolve("audio").resolve("takes").resolve("t").resolve("segment-000.wav");

        String written = ProjectPaths.relativize(root, file.toString());
        String read = ProjectPaths.resolve(root, written);

        assertThat(written).isEqualTo("audio/takes/t/segment-000.wav");
        assertThat(read).isEqualTo(file.toAbsolutePath().normalize().toString());
    }
}
