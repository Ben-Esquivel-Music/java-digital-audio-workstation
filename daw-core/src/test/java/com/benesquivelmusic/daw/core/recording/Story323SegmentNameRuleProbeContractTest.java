package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Story 323 probe, PR #978 Copilot round 2: the tightened segment-name rule of
 * {@link TakeManifest.SegmentEntry}.
 *
 * <p>Pins the clauses of the rule that {@code TakeManifestTest} and
 * {@code Story323ManifestPathProbeContractTest} only sample. Each of the 24
 * stems the rule refuses — the Windows device names {@code con},
 * {@code prn}, {@code aux}, {@code nul}, {@code com1} to {@code com9} and
 * {@code lpt1} to {@code lpt9}, plus {@code com0} and {@code lpt0} — is
 * refused as a whole name, as the stem before one extension, as the stem
 * before several extensions and as a directory name: the stem runs to the
 * <em>first</em> {@code '.'}, as Win32 reads it, so {@code nul.tar.wav} is
 * refused. A device name after the first {@code '.'} is only an extension and
 * is accepted. A trailing {@code '.'} is refused in every name of the path,
 * not only in the last one ({@code t1./x.wav}). And the first character of a
 * name is a lowercase letter or a digit, so a leading {@code '_'} is refused
 * as a leading {@code '.'} or {@code '-'} is.</p>
 */
class Story323SegmentNameRuleProbeContractTest {

    private static final String TRACK = "b7d0c3a4-5e61-4f2a-9c1d-0a1b2c3d4e5f";

    /**
     * The 24 stems the rule refuses, in the order the {@code relativePath} parameter's Javadoc lists
     * them: the four named devices, then {@code com0} to {@code com9}, then {@code lpt0} to {@code lpt9}.
     */
    private static final List<String> DEVICE_STEMS = Stream.concat(
                    Stream.of("con", "prn", "aux", "nul"),
                    Stream.concat(
                            IntStream.rangeClosed(0, 9).mapToObj(digit -> "com" + digit),
                            IntStream.rangeClosed(0, 9).mapToObj(digit -> "lpt" + digit)))
            .toList();

    @TempDir
    Path tempDir;

    @Test
    void everyDocumentedDeviceStemIsRefusedWholeBeforeOneOrSeveralExtensionsAndAsADirectory() {
        assertThat(DEVICE_STEMS).as("fixture: the documented stems").hasSize(24).doesNotHaveDuplicates()
                .contains("com0", "com9", "lpt0", "lpt9");
        List<String> refused = new ArrayList<>();
        for (String stem : DEVICE_STEMS) {
            refused.add(stem);
            refused.add("t1/" + stem + ".wav");
            refused.add("t1/" + stem + ".tar.wav");       // the stem ends at the FIRST '.'
            refused.add(stem + "/segment-000.wav");
        }

        assertEveryPathIsRefused(refused);
    }

    @Test
    void aDeviceNameAfterTheFirstDotIsOnlyAnExtensionAndIsAccepted() {
        Path takeDir = tempDir.resolve("2026-09-30T00-00-00_take-0001");
        for (String stem : DEVICE_STEMS) {
            for (String name : List.of("segment." + stem, "take." + stem + ".wav")) {
                String path = "t1/" + name;
                SegmentEntry entry = new SegmentEntry(TRACK, 0, 0, path, -1, SegmentState.STREAMING);

                assertThat(entry.resolve(takeDir)).as(path).isEqualTo(takeDir.resolve("t1").resolve(name));
            }
        }
    }

    @Test
    void aTrailingDotIsRefusedInEveryNameOfThePathNotOnlyInTheLast() {
        assertEveryPathIsRefused(List.of(
                "t1./x.wav",
                "t1./segment-000.wav",
                "a/b./segment-000.wav",
                "a.b./c/segment-000.wav"));
    }

    @Test
    void aNameStartsWithALowercaseLetterOrADigitSoALeadingUnderscoreIsRefused() {
        assertEveryPathIsRefused(List.of(
                "_x",
                "t1/_segment-000.wav",
                "_t1/segment-000.wav"));
    }

    /** The constructor refuses each path with a message naming the field and ending with the path. */
    private static void assertEveryPathIsRefused(List<String> paths) {
        for (String path : paths) {
            Throwable refusal = catchThrowable(() -> new SegmentEntry(TRACK, 0, 0, path, -1, SegmentState.STREAMING));

            assertThat(refusal)
                    .as(path)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("relativePath")
                    .hasMessageEndingWith(": " + path);
        }
    }
}
