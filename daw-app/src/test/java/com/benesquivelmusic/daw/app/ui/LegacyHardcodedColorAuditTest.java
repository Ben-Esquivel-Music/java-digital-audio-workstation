package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 277 — guards the Phase-3 token-theming contract (UI Design Book
 * §3.1 / §6): every control must consume its colours from a
 * {@code -token} CSS lookup so a theme overlay re-themes it for free.
 * A {@code Color.web(...)} / {@code Color.rgb(...)} call hard-codes a
 * paint that no theme overlay can reach.
 *
 * <p>This is the direct sibling of {@code EveryDialogConformsTest}
 * (story 276): a green gate that scans the {@code
 * com.benesquivelmusic.daw.app.ui} source tree and asserts every file
 * containing a {@code Color.web(} / {@code Color.rgb(} call carries
 * {@link com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed}
 * with a non-blank TODO. The annotation makes the not-yet-tokenized
 * state visible and prevents drift — a new control cannot hard-code a
 * paint without consciously recording why and what the migration is.</p>
 *
 * <p>Story 277's Non-Goals explicitly defer the bulk Canvas/inline-paint
 * tokenization; the audit "tolerates a sentinel {@code
 * @HardcodedColorAllowed} annotation with a TODO" so the debt is
 * tracked, not hidden, exactly as story 276 tracked the not-yet-migrated
 * dialogs with {@code @LegacyDialog}.</p>
 *
 * <h3>Scope justification (why only {@code daw-app/ui}?)</h3>
 *
 * <p>This audit intentionally scans only the {@code
 * com.benesquivelmusic.daw.app.ui} source tree inside the {@code
 * daw-app} module. Other modules that contribute render code ({@code
 * daw-fx}, {@code daw-sdk}) operate at a lower layer: they draw to
 * GPU canvases or render offline buffers where JavaFX CSS tokens are
 * unreachable by design. Those paints are runtime-computed signal
 * representations (waveforms, spectra, meter pixels), not UI chrome,
 * and are not subject to the token theme contract. The audit therefore
 * does not scan them — broadening the root would generate false
 * positives without catching any real theming violations.</p>
 *
 * <p>The scan is a SOURCE-level filesystem text scan (the annotation is
 * {@link java.lang.annotation.RetentionPolicy#SOURCE}, so {@code
 * EveryDialogConformsTest}'s reflective {@code !ann.value().isBlank()}
 * check is unavailable here). To stay faithful to that contract the
 * scan strips {@code //} / {@code /* *\/} comments before looking for a
 * hard-coded paint (so a {@code Color.web(...)} mention in Javadoc does
 * not false-match), blanks string literals before the paint scan, and
 * captures the annotation's {@code value()} literal to assert it is
 * <em>non-blank</em>. The annotation type's own source is excluded by
 * name, exactly as {@code EveryDialogConformsTest} skips the
 * {@code @LegacyDialog} annotation type. Path resolution mirrors
 * {@code NumericClassAuditTest}.</p>
 */
final class LegacyHardcodedColorAuditTest {

    /** {@code Color.web(} or {@code Color.rgb(} — the hard-coded paint calls. */
    private static final Pattern HARDCODED_COLOR =
            Pattern.compile("\\bColor\\.(web|rgb)\\s*\\(");

    /**
     * The class-level sentinel <em>with</em> its mandatory {@code
     * value()} string literal captured (group 1). The audit asserts the
     * captured TODO/exemption text is non-blank — the SOURCE-scan
     * equivalent of {@code EveryDialogConformsTest}'s reflective
     * {@code ann.value() != null && !ann.value().isBlank()}.
     */
    private static final Pattern ANNOTATION_WITH_VALUE = Pattern.compile(
            "@HardcodedColorAllowed\\s*\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    /**
     * One Java comment or one string / text-block literal. Alternation
     * order matters: a literal is matched before {@code //} and {@code
     * /*} so a delimiter inside a string is not mistaken for a comment,
     * and a quote inside a comment is consumed as part of the comment.
     */
    private static final Pattern COMMENT_OR_STRING = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\""        // text block
            + "|\"(?:\\\\.|[^\"\\\\])*\""   // string literal
            + "|//[^\\n]*"                  // line comment
            + "|/\\*[\\s\\S]*?\\*/");       // block comment

    /** {@code daw-app}'s {@code @HardcodedColorAllowed} source — the
     *  annotation type whose own Javadoc necessarily names the calls it
     *  guards; excluded exactly as {@code EveryDialogConformsTest} skips
     *  the {@code @LegacyDialog} annotation type ({@code c.isAnnotation()}). */
    private static final String ANNOTATION_TYPE_FILE = "HardcodedColorAllowed.java";

    @Test
    void everyHardcodedColorSourceCarriesTheSentinelAnnotation() throws IOException {
        Path uiSrcRoot = locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui");
        assertThat(Files.isDirectory(uiSrcRoot))
                .as("UI Java sources must live under %s", uiSrcRoot)
                .isTrue();

        List<String> offenders = new ArrayList<>();
        List<Path> scanned = new ArrayList<>();
        Files.walkFileTree(uiSrcRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java")
                        || name.equals(ANNOTATION_TYPE_FILE)) {
                    return FileVisitResult.CONTINUE;
                }
                scanned.add(file);

                // Strip comments (so a Color.web(...) mention in Javadoc
                // does not false-match) but keep string literals so the
                // real @HardcodedColorAllowed("...") survives for the
                // non-blank check; then blank strings for the paint scan
                // so a Color.web( inside a string is not a false match.
                String code = stripComments(
                        Files.readString(file, StandardCharsets.UTF_8));
                if (!HARDCODED_COLOR.matcher(stripStringLiterals(code)).find()) {
                    return FileVisitResult.CONTINUE;
                }

                String relPath = uiSrcRoot.relativize(file).toString()
                        .replace('\\', '/');
                Matcher ann = ANNOTATION_WITH_VALUE.matcher(code);
                if (!ann.find()) {
                    offenders.add(relPath + "  — missing @HardcodedColorAllowed");
                } else if (ann.group(1).isBlank()) {
                    offenders.add(relPath + "  — @HardcodedColorAllowed value "
                            + "is blank (the migration TODO / exemption "
                            + "rationale is mandatory)");
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // Guard against a silently-empty scan (a broken path would make
        // the audit vacuously pass).
        assertThat(scanned)
                .as("the UI source scan must visit a non-trivial number of "
                        + ".java files — an empty scan would make this audit "
                        + "vacuously pass")
                .hasSizeGreaterThan(50);

        offenders.sort(String::compareTo);
        assertThat(offenders)
                .as("Story 277 — every UI source file containing a "
                        + "Color.web(...) / Color.rgb(...) call must consume "
                        + "colour from a -token CSS lookup, or carry "
                        + "@HardcodedColorAllowed(\"<TODO>\") to record the "
                        + "deferred migration (UI Design Book §3.1 / §6, "
                        + "sibling of story 276's @LegacyDialog). "
                        + "Non-conforming files:%n  %s%nAdd the sentinel "
                        + "annotation with a migration TODO, or tokenize the "
                        + "paint.",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    /**
     * Locate the {@code daw-app} module root. Surefire normally sets the
     * working directory to the module itself; the fallbacks cover
     * invocations from the repo root (e.g. {@code mvn -pl daw-app test}).
     * Mirrors {@code NumericClassAuditTest#locateDawAppModule()}.
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

    // ── source pre-processing ────────────────────────────────────────────────

    /**
     * Removes {@code //} and {@code /* *\/} comments while preserving
     * string / text-block literals (so a real {@code
     * @HardcodedColorAllowed("...")} survives the strip but a {@code
     * Color.web(...)} written inside Javadoc does not). Mirrors the
     * DOTALL comment-stripping {@code TokenValidationTest} already does,
     * generalised to skip over string literals.
     */
    private static String stripComments(String source) {
        Matcher m = COMMENT_OR_STRING.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        while (m.find()) {
            String token = m.group();
            // A string / text block (starts with a quote) is code — keep
            // it verbatim; anything else the alternation matched is a
            // comment and is replaced with a single space.
            m.appendReplacement(out, token.charAt(0) == '"'
                    ? Matcher.quoteReplacement(token) : " ");
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Blanks string / text-block literals out of already comment-free code. */
    private static String stripStringLiterals(String code) {
        return code
                .replaceAll("\"\"\"[\\s\\S]*?\"\"\"", "\"\"")
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }
}
