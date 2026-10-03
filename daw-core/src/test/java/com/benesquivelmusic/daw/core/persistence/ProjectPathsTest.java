package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.ProjectPaths.Unresolvable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one write/read rule shared by {@code ProjectSerializer} and
 * {@code ProjectDeserializer} (story 323; Persistence book §3.3), and the
 * in-memory rule the consumers of a clip reference follow.
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
    void resolveKeepsAReferenceWhoseTargetIsNotStrictlyUnderTheRootVerbatim() {
        // '/' escapes on Windows and on Linux alike ("..\x" would be one file
        // name on Linux), so these hold on both.
        for (String escaping : List.of("../outside.wav", "audio/../../outside.wav", "..", ".", "audio/..")) {
            assertThat(ProjectPaths.resolve(root, escaping)).as(escaping).isEqualTo(escaping);
        }
    }

    @Test
    void resolveJudgesContainmentOnTheNormalisedTargetSoAWalkBackIntoTheRootIsResolved() {
        String walkBack = "../" + root.getFileName() + "/audio/x.wav";
        String expected = root.toAbsolutePath().normalize().resolve("audio").resolve("x.wav").toString();

        assertThat(ProjectPaths.resolve(root, walkBack)).isEqualTo(expected);
    }

    @Test
    void unresolvableNamesTheReasonForExactlyTheReferencesThatAreNeitherProjectRelativeNorAbsolute() {
        String insideAbsolute = root.resolve("audio").resolve("x.wav").toAbsolutePath().toString();
        String outsideAbsolute = root.resolveSibling("elsewhere").resolve("x.wav").toAbsolutePath().toString();

        for (String usable : List.of("audio/x.wav", "audio/./x.wav", "../" + root.getFileName() + "/audio/x.wav",
                insideAbsolute, outsideAbsolute)) {
            assertThat(ProjectPaths.unresolvable(root, usable)).as("usable: %s", usable).isEmpty();
        }
        for (String outside : List.of("../outside.wav", "audio/../../outside.wav", "..")) {
            assertThat(ProjectPaths.unresolvable(root, outside)).as(outside)
                    .contains(Unresolvable.ESCAPES_THE_DIRECTORY);
        }
        for (String itself : List.of(".", "audio/..")) {
            assertThat(ProjectPaths.unresolvable(root, itself)).as(itself)
                    .contains(Unresolvable.IS_THE_DIRECTORY);
        }
        assertThat(ProjectPaths.unresolvable(root, UNPARSEABLE))
                .as("a NUL character parses on no platform")
                .contains(Unresolvable.NOT_A_VALID_PATH);
        assertThat(ProjectPaths.unresolvable(null, "../outside.wav")).as("no root").isEmpty();
        assertThat(ProjectPaths.unresolvable(null, UNPARSEABLE)).as("no root, unparseable").isEmpty();
        assertThat(ProjectPaths.unresolvable(root, null)).as("null reference").isEmpty();
        assertThat(ProjectPaths.unresolvable(root, "")).as("empty reference").isEmpty();
    }

    @Test
    void withARootEveryNonEmptyReferenceComesBackAbsoluteOrIsUnresolvableAndKeptAsWritten() {
        List<String> references = List.of("audio/x.wav", "audio/./x.wav", "../" + root.getFileName() + "/audio/x.wav",
                "../outside.wav", "audio/../../outside.wav", "..", ".", "audio/..", "/x.wav", "C:x.wav",
                "\\x.wav", "..\\outside.wav", "bad|name.wav", "  ", "...", UNPARSEABLE,
                root.resolve("x.wav").toAbsolutePath().toString());
        for (String reference : references) {
            boolean unresolvable = ProjectPaths.unresolvable(root, reference).isPresent();
            String inMemory = ProjectPaths.resolve(root, reference);

            assertThat(unresolvable)
                    .as("unresolvable exactly when the in-memory form is not absolute: [%s]", reference)
                    .isNotEqualTo(ProjectPaths.isAbsoluteReference(inMemory));
            if (unresolvable) {
                assertThat(inMemory).as("kept as written: [%s]", reference).isEqualTo(reference);
            }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsDriveAndRootRelativeReferencesAreUnresolvableAndABackslashWalkEscapes() {
        for (String rooted : List.of("C:x.wav", "\\x.wav", "/x.wav")) {
            assertThat(ProjectPaths.unresolvable(root, rooted)).as(rooted)
                    .contains(Unresolvable.DRIVE_OR_ROOT_RELATIVE);
            assertThat(ProjectPaths.resolve(root, rooted)).as(rooted).isEqualTo(rooted);
            assertThat(ProjectPaths.isAbsoluteReference(rooted)).as(rooted).isFalse();
        }
        assertThat(ProjectPaths.unresolvable(root, "..\\outside.wav"))
                .contains(Unresolvable.ESCAPES_THE_DIRECTORY);
        for (String unparseable : List.of("bad|name.wav", "ends-with-a-space ", "tab\tname.wav")) {
            assertThat(ProjectPaths.unresolvable(root, unparseable)).as(unparseable)
                    .contains(Unresolvable.NOT_A_VALID_PATH);
            assertThat(ProjectPaths.resolve(root, unparseable)).as(unparseable).isEqualTo(unparseable);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void onLinuxTheWindowsFormsAreSingleNamesUnderTheRootAndALeadingSlashIsAbsolute() {
        Path rootAbs = root.toAbsolutePath().normalize();
        for (String name : List.of("C:x.wav", "\\x.wav", "..\\outside.wav", "bad|name.wav")) {
            assertThat(ProjectPaths.unresolvable(root, name)).as(name).isEmpty();
            assertThat(ProjectPaths.resolve(root, name)).as(name).isEqualTo(rootAbs.resolve(name).toString());
            assertThat(ProjectPaths.isAbsoluteReference(name)).as(name).isFalse();
        }
        assertThat(ProjectPaths.unresolvable(root, "/x.wav")).isEmpty();
        assertThat(ProjectPaths.resolve(root, "/x.wav")).isEqualTo("/x.wav");
        assertThat(ProjectPaths.isAbsoluteReference("/x.wav")).isTrue();
    }

    @Test
    void isAbsoluteReferenceHoldsExactlyForAnAbsolutePathOnThisPlatform() {
        assertThat(ProjectPaths.isAbsoluteReference(root.resolve("x.wav").toAbsolutePath().toString())).isTrue();
        for (String relative : List.of("x.wav", "audio/x.wav", "../outside.wav", "..", ".")) {
            assertThat(ProjectPaths.isAbsoluteReference(relative)).as(relative).isFalse();
        }
        assertThat(ProjectPaths.isAbsoluteReference(UNPARSEABLE)).as("unparseable").isFalse();
        assertThat(ProjectPaths.isAbsoluteReference("")).as("empty").isFalse();
        assertThat(ProjectPaths.isAbsoluteReference(null)).as("null").isFalse();
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
