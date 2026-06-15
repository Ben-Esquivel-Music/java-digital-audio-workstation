package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
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
 * matching — exactly as {@code RunLaterConsolidationTest} does — so a Javadoc
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

    /**
     * One Java comment or one string / text-block literal. Alternation order
     * matters: a literal is matched before {@code //} and {@code /*} so a
     * delimiter inside a string is not mistaken for a comment, and a quote inside
     * a comment is consumed as part of the comment. Mirrors
     * {@code RunLaterConsolidationTest#COMMENT_OR_STRING}.
     */
    private static final Pattern COMMENT_OR_STRING = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\""        // text block
            + "|\"(?:\\\\.|[^\"\\\\])*\""   // string literal
            + "|//[^\\n]*"                  // line comment
            + "|/\\*[\\s\\S]*?\\*/");       // block comment

    /** The four source files that make up the strip and its cell control. */
    private static final List<String> STRIP_SOURCE_FILES = List.of(
            "SessionStatusStrip.java",
            "SessionStatusStripSkin.java",
            "StatusStripCell.java",
            "StatusStripCellSkin.java");

    @Test
    void stripSourcesDriveTextOnlyViaBindingNeverLabelSetText() throws IOException {
        Path statusDir = locateDawAppModule()
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
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
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

    /**
     * Locate the {@code daw-app} module root. Surefire normally sets the working
     * directory to the module itself; the fallbacks cover invocations from the
     * repo root (e.g. {@code mvn -pl daw-app test}). Mirrors
     * {@code RunLaterConsolidationTest#locateDawAppModule()}.
     */
    private static Path locateDawAppModule() {
        Path cwd = Paths.get("").toAbsolutePath();
        if (isDawAppModule(cwd)) {
            return cwd;
        }
        Path child = cwd.resolve("daw-app");
        if (isDawAppModule(child)) {
            return child;
        }
        Path candidate = cwd.getParent();
        for (int i = 0; i < 5 && candidate != null; i++) {
            if (isDawAppModule(candidate)) {
                return candidate;
            }
            Path nested = candidate.resolve("daw-app");
            if (isDawAppModule(nested)) {
                return nested;
            }
            candidate = candidate.getParent();
        }
        return cwd;
    }

    private static boolean isDawAppModule(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isDirectory(
                        dir.resolve("src/main/java/com/benesquivelmusic/daw/app"));
    }

    /**
     * Removes {@code //} and {@code /* *\/} comments while preserving string /
     * text-block literals. Mirrors {@code RunLaterConsolidationTest#stripComments}.
     */
    private static String stripComments(String source) {
        Matcher m = COMMENT_OR_STRING.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        while (m.find()) {
            String token = m.group();
            // A string / text block (starts with a quote) is code — keep it
            // verbatim; anything else the alternation matched is a comment and is
            // replaced with a single space.
            m.appendReplacement(out, token.charAt(0) == '"'
                    ? Matcher.quoteReplacement(token) : " ");
        }
        m.appendTail(out);
        return out.toString();
    }
}
