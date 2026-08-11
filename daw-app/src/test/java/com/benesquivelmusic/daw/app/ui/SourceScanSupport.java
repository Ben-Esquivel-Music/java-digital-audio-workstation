package com.benesquivelmusic.daw.app.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared harness for the source-scan conformance sentinels (the
 * {@code RunLaterConsolidationTest} / {@code LegacyHardcodedColorAuditTest} /
 * {@code NoClassNameFieldAnywhereTest} family): locating the {@code daw-app}
 * module root, and the comment / string-literal preprocessing a SOURCE-level
 * scan applies before matching. Extracted from the per-test copies (story 304
 * review) so the scanners cannot drift apart — one harness, many scans.
 *
 * <p>Each sentinel keeps its own scan scope, forbidden-token patterns, and
 * offender messaging; only the mechanics that must never diverge live
 * here.</p>
 */
public final class SourceScanSupport {

    /**
     * One Java comment or one string / text-block / char literal. Alternation
     * order matters: a literal is matched before {@code //} and {@code /*} so
     * a delimiter inside a string is not mistaken for a comment, and a quote
     * inside a comment is consumed as part of the comment. Char literals are
     * tokenized too, ahead of the comment alternatives: a quote-bearing char
     * literal ({@code '"'}) would otherwise open a phantom string that
     * swallows real code up to the next quote (story 314 review).
     */
    private static final Pattern COMMENT_OR_STRING = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\""        // text block
            + "|\"(?:\\\\.|[^\"\\\\])*\""   // string literal
            + "|'(?:\\\\.|[^'\\\\])'"       // char literal
            + "|//[^\\n]*"                  // line comment
            + "|/\\*[\\s\\S]*?\\*/");       // block comment

    private SourceScanSupport() {
    }

    /**
     * Locate the {@code daw-app} module root. Surefire normally sets the
     * working directory to the module itself; the fallbacks cover invocations
     * from the repo root (e.g. {@code mvn -pl daw-app test}) and nested
     * layouts. Returns the working directory as a last resort, so a caller's
     * own is-a-directory assertion fails with a clear message rather than the
     * scan passing vacuously.
     */
    public static Path locateDawAppModule() {
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
     * text-block literals — a SOURCE-level scan must not false-match a
     * construct merely mentioned in Javadoc, while a real sentinel
     * annotation's {@code "reason"} literal must survive the strip.
     */
    public static String stripComments(String source) {
        Matcher m = COMMENT_OR_STRING.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        while (m.find()) {
            String token = m.group();
            // A string / text block / char literal (starts with a quote) is
            // code — keep it verbatim; anything else the alternation matched
            // is a comment and is replaced with a single space.
            char first = token.charAt(0);
            m.appendReplacement(out, first == '"' || first == '\''
                    ? Matcher.quoteReplacement(token) : " ");
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Blanks string / text-block / char literals out of already comment-free
     * code. Char literals are consumed FIRST so a quote-bearing char literal
     * ({@code '"'}) cannot pair with a real string delimiter and blank the
     * code between them (story 314 review).
     */
    public static String stripStringLiterals(String code) {
        return code
                .replaceAll("'(?:\\\\.|[^'\\\\])'", "''")
                .replaceAll("\"\"\"[\\s\\S]*?\"\"\"", "\"\"")
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }
}
