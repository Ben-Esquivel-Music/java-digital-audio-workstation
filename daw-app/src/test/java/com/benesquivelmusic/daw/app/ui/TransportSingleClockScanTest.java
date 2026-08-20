package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 315 review — source-scan sentinels for the three "one source of truth"
 * rules the review restored (the {@code TransportTimeSourceRetiredTest} family,
 * sharing {@link SourceScanSupport}). Each rule is a structural invariant that a
 * behavioural test cannot pin deterministically — a torn read is a race, an
 * absent paint is an absence, and a duplicated helper is a maintenance hazard —
 * so it is asserted where it actually lives: in the source.
 *
 * <ul>
 *   <li><strong>Tear-free loop read.</strong> {@code TransportVM} and
 *       {@code MainController} must read the loop trio through the single
 *       {@code Transport.getLoopWindow()} snapshot. Calling
 *       {@code isLoopEnabled()} / {@code getLoopStartInBeats()} /
 *       {@code getLoopEndInBeats()} separately can stitch an enabled flag from
 *       one window onto bounds from another.</li>
 *   <li><strong>One playhead writer.</strong> {@code MainController.seekToPosition}
 *       must not paint the ruler or canvas playhead. While the RT clock owns the
 *       transport a seek lands only at the next block boundary, so an imperative
 *       paint of the requested beat fights the per-frame tick that repaints from
 *       the (still old) VM playhead — visibly at large buffer sizes.</li>
 *   <li><strong>One frame conversion.</strong> {@code TransportController} must
 *       not declare its own {@code beatsToFrames} / {@code sampleRate}; the
 *       conversion behind every {@code TransportEvent} frame field belongs to
 *       {@code CoreTransportIntentHandler} alone.</li>
 * </ul>
 *
 * <p>Comments and string literals are stripped before matching, so the prose
 * above (and the Javadoc in the scanned files) cannot false-match; each scan
 * also asserts a positive marker so it can never pass vacuously against a file
 * it failed to find.</p>
 */
final class TransportSingleClockScanTest {

    private static final String[] TORN_LOOP_GETTERS = {
            "isLoopEnabled(", "getLoopStartInBeats(", "getLoopEndInBeats("
    };

    @Test
    void theLoopTrioIsReadAsOneSnapshotByTheViewModelAndTheOverlayPainter() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : List.of(
                mainSource("ui/vm/TransportVM.java"),
                mainSource("ui/MainController.java"))) {
            String code = codeOf(file);
            assertThat(code)
                    .as("%s must read the loop window as one snapshot — its absence "
                            + "would mean this scan found the wrong file", file.getFileName())
                    .contains("getLoopWindow()");
            for (String getter : TORN_LOOP_GETTERS) {
                if (code.contains(getter)) {
                    offenders.add(file.getFileName() + " calls " + getter + ')');
                }
            }
        }
        assertThat(offenders)
                .as("the loop trio must come from Transport.getLoopWindow(), never from "
                        + "three separate volatile reads; offenders: %s", offenders)
                .isEmpty();
    }

    @Test
    void seekToPositionDoesNotPaintThePlayheadItself() throws IOException {
        String body = methodBody(codeOf(mainSource("ui/MainController.java")),
                "private void seekToPosition(double beat) {");

        assertThat(body)
                .as("the extracted body must still perform the seek — otherwise this "
                        + "scan is reading the wrong method")
                .contains("setPositionInBeats(");
        assertThat(body)
                .as("the ruler playhead is painted by the per-frame tick from the VM, "
                        + "never imperatively at the requested beat")
                .doesNotContain("setPlayheadPositionBeats");
        assertThat(body)
                .as("the canvas playhead is painted by the per-frame tick from the VM, "
                        + "never imperatively at the requested beat")
                .doesNotContain("setPlayheadBeat");
    }

    @Test
    void theTransportControllerKeepsNoCopyOfTheFrameConversion() throws IOException {
        String code = codeOf(mainSource("ui/TransportController.java"));

        assertThat(code)
                .as("the controller must convert through the handler that owns the "
                        + "conversion — its absence would mean this scan found the wrong file")
                .contains("core.beatsToFrames(");
        assertThat(code)
                .as("a second beatsToFrames declaration would have to be corrected twice "
                        + "(e.g. to honour the TempoMap)")
                .doesNotContain("long beatsToFrames(");
        assertThat(code)
                .as("the duplicated sampleRate field went with the duplicated conversion")
                .doesNotContain("double sampleRate;");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Path mainSource(String relativeToUiPackage) {
        return SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app")
                .resolve(relativeToUiPackage);
    }

    /** Reads {@code file} with comments stripped (string literals survive). */
    private static String codeOf(Path file) throws IOException {
        assertThat(Files.isRegularFile(file))
                .as("scanned source must exist: %s", file).isTrue();
        return SourceScanSupport.stripComments(Files.readString(file));
    }

    /**
     * Returns the brace-balanced body that follows {@code declaration} in
     * {@code code}. Comments are already stripped by {@link #codeOf(Path)}, so
     * only real braces are counted; string literals in these bodies contain no
     * braces.
     */
    private static String methodBody(String code, String declaration) {
        int start = code.indexOf(declaration);
        assertThat(start)
                .as("declaration not found — the scan must be updated with the method: %s",
                        declaration)
                .isNotNegative();
        int cursor = start + declaration.length();
        int depth = 1;
        while (cursor < code.length() && depth > 0) {
            char c = code.charAt(cursor++);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        assertThat(depth).as("unbalanced braces after %s", declaration).isZero();
        return code.substring(start + declaration.length(), cursor - 1);
    }
}
