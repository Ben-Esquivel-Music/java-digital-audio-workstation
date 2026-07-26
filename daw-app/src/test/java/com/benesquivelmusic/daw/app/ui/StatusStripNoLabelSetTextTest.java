package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 295 — guards the §8 "no status text in {@code Label.setText}" contract
 * for the Session Status Strip (Project Manager Design Book §5.5;
 * {@code javafx-application-design} §15 "a control is observable Properties, not
 * imperative setters"). The strip and its cells are <em>binding-only</em>: every
 * visible status the strip renders is one observable {@code Property} on
 * {@link com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress}, and the
 * cells drive their text exclusively via {@code textProperty().bind(...)} — never
 * by poking a {@link javafx.scene.control.Label}/{@link
 * javafx.scene.control.Labeled} with {@code setText(...)}.
 *
 * <p>This is the source-scan sibling of {@code RunLaterConsolidationTest}: a green
 * gate that scans the strip's four source files and prevents drift — a future edit
 * cannot reintroduce an imperative {@code Label.setText} that would let the strip's
 * displayed text diverge from the model it is meant to mirror.</p>
 *
 * <h3>Scope &amp; preprocessing</h3>
 *
 * <p>The scan covers exactly the four strip source files under {@code
 * …app.ui.status}: {@code SessionStatusStrip.java}, {@code
 * SessionStatusStripSkin.java}, {@code StatusStripCell.java} and {@code
 * StatusStripCellSkin.java}. Each is asserted to exist and to be scanned (a guard
 * against a vacuously-empty scan from a broken path). To stay faithful to a
 * SOURCE-level scan it strips {@code //} and {@code /* *\/} comments before
 * matching — via the shared {@link SourceScanSupport} harness — so a Javadoc
 * {@code {@code Label.setText}} mention does not false-match. It then looks for a
 * dotted {@code .setText(} invocation (a receiver-qualified text mutation); the
 * dot requirement means a method <em>named</em> {@code setText} declared in the
 * source (none of these files declares one) would not match on its own
 * declaration line either.</p>
 */
final class StatusStripNoLabelSetTextTest {

    /**
     * A receiver-qualified {@code .setText(} call — the imperative
     * {@link javafx.scene.control.Labeled#setText(String)} mutation this story
     * rejects. The leading dot requires a receiver (e.g. {@code label.setText(}),
     * so it matches a call expression rather than a method declaration.
     */
    private static final Pattern DOTTED_SET_TEXT =
            Pattern.compile("\\.\\s*setText\\s*\\(");

    /** The four source files that make up the strip and its cell control. */
    private static final List<String> STRIP_SOURCE_FILES = List.of(
            "SessionStatusStrip.java",
            "SessionStatusStripSkin.java",
            "StatusStripCell.java",
            "StatusStripCellSkin.java");

    @Test
    void stripSourcesDriveTextOnlyViaBindingNeverLabelSetText() throws IOException {
        Path statusDir = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/status");
        assertThat(Files.isDirectory(statusDir))
                .as("the Session Status Strip sources must live under %s", statusDir)
                .isTrue();

        List<String> offenders = new ArrayList<>();
        List<String> scanned = new ArrayList<>();

        for (String fileName : STRIP_SOURCE_FILES) {
            Path file = statusDir.resolve(fileName);
            assertThat(Files.isRegularFile(file))
                    .as("strip source file must exist: %s", file)
                    .isTrue();
            scanned.add(fileName);

            // Strip comments (so a {@code Label.setText} mention in Javadoc does
            // not false-match) but keep string literals — a setText written inside
            // a string literal would still be code worth flagging, and none of
            // these files legitimately contains the substring in a string.
            String code = SourceScanSupport.stripComments(
                    Files.readString(file, StandardCharsets.UTF_8));
            if (DOTTED_SET_TEXT.matcher(code).find()) {
                offenders.add(fileName
                        + "  — contains a dotted .setText(...) call; the strip must "
                        + "drive text via textProperty().bind(...) only (story 295 §8)");
            }
        }

        // Guard against a silently-empty scan — if the path resolved wrong and no
        // file were read, the offenders list would be vacuously empty.
        assertThat(scanned)
                .as("all four strip source files must be scanned")
                .containsExactlyInAnyOrderElementsOf(STRIP_SOURCE_FILES);

        offenders.sort(String::compareTo);
        assertThat(offenders)
                .as("Story 295 — the Session Status Strip is binding-only "
                        + "(javafx-application-design §15): its cells must bind "
                        + "their text to ProjectOperationProgress properties and "
                        + "never call Label/Labeled.setText(...). Offending files:%n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }
}
