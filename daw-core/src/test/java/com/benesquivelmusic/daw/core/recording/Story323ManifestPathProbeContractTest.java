package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Story 323 probe, PR #978 Copilot round, finding F1 (a take manifest's
 * segment path must not lead out of its take directory).
 *
 * <p>Pins what {@code TakeManifestTest} leaves open or checks less closely.
 * The segment-path rule is lexical: a {@code ..} step is refused even where
 * normalising would bring the path back inside the take directory, so a rule
 * that merely tracks the depth would fail here. Names that only resemble
 * refused ones ({@code a.b.c}, {@code x-1}, {@code take_2}, {@code console},
 * {@code com10}, {@code nul-1}, a name that starts with a digit, a
 * 204-character name, fifty names deep) are accepted and resolve name by name
 * under the take directory, so a rule stricter than the documented one would
 * fail here. And {@link SegmentEntry#relativePathFor} judges containment name
 * by name on the normalised paths of both sides: a sibling whose name merely
 * starts with the take directory's name is refused, a file that climbs out
 * through {@code ..} is refused, and a take directory given in a
 * non-normalised form still accepts its own files.</p>
 */
class Story323ManifestPathProbeContractTest {

    private static final String TAKE = "2026-09-29T08-15-00_take-0042";
    private static final String TRACK = "b7d0c3a4-5e61-4f2a-9c1d-0a1b2c3d4e5f";

    @TempDir
    Path tempDir;

    @Test
    void aParentStepEmptyNameOrDriveDesignatorIsRefusedEvenWhereNormalisingWouldStayInside() {
        List<String> refused = List.of(
                "a/b/../../../escape.wav",   // three levels up from two down
                "lane-1/../segment-000.wav", // normalises back inside the take: still refused
                "t2/segment-000.wav/..",     // a trailing parent step
                "/",                         // a lone separator: two empty names
                "///segment-000.wav",
                "t2/./",
                "D:",                        // a bare drive designator, a capital-letter case
                "t2/D:segment-000.wav",      // a drive designator after a separator, a capital-letter case
                "d:",                        // a bare drive designator: refused for its ':' alone
                "t2/d:segment-000.wav");     // a drive designator after a separator: the same
        for (String path : refused) {
            // Caught, then asserted: a path that is wrongly accepted fails the test under its own name.
            Throwable refusal = catchThrowable(() -> new SegmentEntry(TRACK, 0, 0, path, -1, SegmentState.STREAMING));
            assertThat(refusal)
                    .as(path)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(path);
        }
    }

    @Test
    void namesThatOnlyResembleRefusedOnesAreAcceptedAndResolveNameByNameUnderTheTakeDirectory() {
        Path takeDir = tempDir.resolve(TAKE);
        String longName = "x".repeat(200) + ".wav";
        String fiftyNames = IntStream.range(0, 49).mapToObj(i -> "d" + i).collect(Collectors.joining("/"))
                + "/segment-000.wav";
        List<String> accepted = List.of(
                "a.b.c",                                   // dots inside a name, none at its end
                "x-1", "t2/x-1/segment-000.wav",           // a hyphen after the first character
                "take_2", "t2/take_2.wav",                 // an underscore
                "console", "t2/console.wav",               // a device name's prefix, not its stem
                "com10", "t2/com10/segment-000.wav",
                "nul-1", "nul-1/segment-000.wav",          // the stem runs to the first '.', past the '-'
                "9f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b/segment-000.wav", // a first digit, as in a track id
                longName,                                  // 204 characters
                fiftyNames);

        for (String path : accepted) {
            SegmentEntry entry = new SegmentEntry(TRACK, 0, 0, path, -1, SegmentState.STREAMING);
            String[] names = path.split("/");
            Path expected = takeDir;
            for (String name : names) {
                expected = expected.resolve(name);
            }

            Path resolved = entry.resolve(takeDir);
            assertThat(resolved).as(path).isEqualTo(expected);
            assertThat(resolved.getNameCount()).as(path).isEqualTo(takeDir.getNameCount() + names.length);
            assertThat(resolved.normalize()).as("%s has no '.' or '..' element", path).isEqualTo(resolved);
            assertThat(resolved.startsWith(takeDir)).as(path).isTrue();
        }
        assertThat(fiftyNames.split("/")).as("fixture").hasSize(50);
    }

    @Test
    void relativePathForRefusesASiblingWhoseNameMerelyStartsWithTheTakeDirectoryName() {
        Path takeDir = tempDir.resolve(TAKE);
        Path lookalike = tempDir.resolve(TAKE + "-copy").resolve("t2").resolve("segment-000.wav");
        Path extended = tempDir.resolve(TAKE + "1").resolve("segment-000.wav");

        for (Path outside : List.of(lookalike, extended)) {
            assertThat(outside.toAbsolutePath().normalize().toString())
                    .as("fixture: a string-prefix test would call %s inside", outside)
                    .startsWith(takeDir.toAbsolutePath().normalize().toString());
            assertThatThrownBy(() -> SegmentEntry.relativePathFor(takeDir, outside))
                    .as(outside.toString())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(outside.toString());
        }
    }

    @Test
    void relativePathForJudgesTheNormalisedPathsOfBothSides() {
        Path takeDir = tempDir.resolve(TAKE);
        Path climbsOut = takeDir.resolve("t2").resolve("..").resolve("..").resolve("escape.wav");
        Path backToTheTake = takeDir.resolve("t2").resolve("..");
        Path dotted = takeDir.resolve(".");
        for (Path notInside : List.of(climbsOut, backToTheTake, dotted)) {
            assertThatThrownBy(() -> SegmentEntry.relativePathFor(takeDir, notInside))
                    .as(notInside.toString())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        Path wandering = takeDir.resolve("t2").resolve("lane-1").resolve("..").resolve(".").resolve("segment-000.wav");
        assertThat(SegmentEntry.relativePathFor(takeDir, wandering)).isEqualTo("t2/segment-000.wav");

        Path unnormalisedTakeDir = tempDir.resolve("elsewhere").resolve("..").resolve(TAKE);
        assertThat(SegmentEntry.relativePathFor(unnormalisedTakeDir, takeDir.resolve("t2").resolve("segment-000.wav")))
                .isEqualTo("t2/segment-000.wav");
    }
}
