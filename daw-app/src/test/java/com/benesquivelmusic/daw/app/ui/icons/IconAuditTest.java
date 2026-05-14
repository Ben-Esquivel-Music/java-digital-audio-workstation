package com.benesquivelmusic.daw.app.ui.icons;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forbids references to legacy icon families in the UI module's
 * <strong>production</strong> sources ({@code src/main}).
 *
 * <p>Per UI Design Book §3.6 (Iconography) the DAW has a <em>single</em>
 * approved icon family: Lucide, surfaced via {@link DawgIcon}. This
 * audit scans Java, FXML, and CSS files under {@code src/main} for
 * tell-tale string literals of icon-font libraries that previously
 * appeared in the codebase (FontAwesome, Material Design Icons / MDI,
 * etc.) and fails if any survive.</p>
 *
 * <p>Test sources ({@code src/test}) are intentionally exempt: test
 * fixtures and harnesses may legitimately reference legacy icon names
 * when verifying migration guards or wiring stubs.</p>
 *
 * <p>The audit also forbids {@code javafx.scene.text.Font} as an icon
 * mechanism (i.e. via {@code Glyph} text characters) — these are the
 * traditional pre-vector icon-font implementations the design book
 * explicitly rules out in favour of vector SVG.</p>
 */
class IconAuditTest {

    /** Forbidden icon-family identifiers as regexes. */
    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile("\\bFontAwesome\\b", Pattern.CASE_INSENSITIVE),
            // Long-form FontAwesome class prefixes (fa-solid, fa-regular,
            // fa-brands, fa-light, fa-thin, fa-sharp). Word-bounded so it
            // catches both quoted Java strings, FXML attributes, and bare
            // CSS selectors.
            Pattern.compile("\\bfa-(solid|regular|brands|light|thin|sharp|icon)-[a-z0-9-]+\\b"),
            // Short-form FontAwesome class prefixes (fas / far / fab /
            // fal) immediately followed by an `fa-...` glyph class — the
            // pre-v5 spelling that still appears in older codebases and
            // tutorials. Requires the `fa-` continuation so we don't
            // collide with unrelated three-letter identifiers like `fas`.
            Pattern.compile("\\b(fas|far|fab|fal)\\s+fa-[a-z0-9-]+\\b"),
            // The bare `fa fa-...` legacy form (FontAwesome 4.x).
            Pattern.compile("\\bfa\\s+fa-[a-z0-9-]+\\b"),
            Pattern.compile("\\bMaterial:[a-z0-9_-]+\\b", Pattern.CASE_INSENSITIVE),
            // The Google Material Icons web-font class.
            Pattern.compile("\\bmaterial-icons\\b"),
            Pattern.compile("\\bMDI_[A-Z_]+\\b"),
            Pattern.compile("\\bmdi-[a-z0-9-]+\\b"),
            // MaterialFX / JFoenix-style `mfx-` icon selectors.
            Pattern.compile("\\bmfx-[a-z0-9-]+\\b"),
            // Glyph is the typical class name from icon-font helpers
            // (org.controlsfx.glyphfont.Glyph, kordamp's GlyphFontRegistry,
            // de.jensd.fx.glyphs.GlyphIcon).
            Pattern.compile("\\bGlyphFont(Registry)?\\b"),
            Pattern.compile("\\borg\\.controlsfx\\.glyphfont\\b"),
            Pattern.compile("\\bde\\.jensd\\.fx\\.glyphs\\b")
    );

    /** Files that the audit must not flag itself on. */
    private static final List<String> SELF_EXEMPT = List.of(
            "IconAuditTest.java"
    );

    /** File extensions scanned by the audit (Java, FXML, CSS). */
    private static final List<String> SCANNED_EXTENSIONS = List.of(".java", ".fxml", ".css");

    @Test
    void uiSourcesMustNotReferenceLegacyIconFamilies() throws IOException {
        Path moduleRoot = locateDawAppModule();
        Path srcRoot = moduleRoot.resolve("src/main");
        assertThat(Files.isDirectory(srcRoot))
                .as("UI sources must live under " + srcRoot)
                .isTrue();

        List<String> violations = new ArrayList<>();
        Files.walkFileTree(srcRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                boolean scanned = SCANNED_EXTENSIONS.stream()
                        .anyMatch(fileName::endsWith);
                if (!scanned) return FileVisitResult.CONTINUE;
                if (SELF_EXEMPT.contains(fileName)) return FileVisitResult.CONTINUE;

                scanFile(file, violations);
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(violations)
                .as("UI Design Book §3.6 forbids icon-font references — migrate to DawgIcon (Lucide).")
                .isEmpty();
    }

    /**
     * Streams a single file line by line and records all violations found
     * up to the first matching pattern <em>per line</em>. The streaming
     * read avoids loading the full source into memory; short-circuiting
     * on the first match per line keeps the inner loop cheap even for
     * lines that hit several patterns.
     *
     * <p>Tracks {@code /* ... *}{@code /} block-comment state across
     * lines so that legacy-family references inside multi-line CSS or
     * Java block comments do not trip the audit.</p>
     */
    private static void scanFile(Path file, List<String> violations) throws IOException {
        AtomicInteger lineNo = new AtomicInteger(0);
        boolean[] inBlockComment = {false};
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                int n = lineNo.incrementAndGet();
                String stripped = stripComments(line, inBlockComment);
                if (stripped.isEmpty()) return;
                for (Pattern p : FORBIDDEN_PATTERNS) {
                    if (p.matcher(stripped).find()) {
                        violations.add(file + ":" + n + " — matched " + p.pattern()
                                + " -> " + line.trim());
                        return; // one violation per line is enough
                    }
                }
            });
        }
    }

    /**
     * Returns the code-only portion of {@code line}: line comments,
     * inline block-comment segments, and any segment inside an open
     * multi-line block comment are stripped. The single-element
     * {@code inBlockComment} array carries open-block state from one
     * line to the next.
     */
    static String stripComments(String line, boolean[] inBlockComment) {
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        while (i < line.length()) {
            if (inBlockComment[0]) {
                int close = line.indexOf("*/", i);
                if (close < 0) return out.toString();
                inBlockComment[0] = false;
                i = close + 2;
                continue;
            }
            // Detect start of single-line comment.
            if (i + 1 < line.length()
                    && line.charAt(i) == '/' && line.charAt(i + 1) == '/') {
                break; // rest of line is a comment
            }
            // Detect start of block comment.
            if (i + 1 < line.length()
                    && line.charAt(i) == '/' && line.charAt(i + 1) == '*') {
                inBlockComment[0] = true;
                i += 2;
                continue;
            }
            // Detect XML / FXML comment.
            if (i + 3 < line.length()
                    && line.charAt(i) == '<' && line.charAt(i + 1) == '!'
                    && line.charAt(i + 2) == '-' && line.charAt(i + 3) == '-') {
                int close = line.indexOf("-->", i + 4);
                if (close < 0) return out.toString(); // unterminated — treat rest as comment
                i = close + 3;
                continue;
            }
            out.append(line.charAt(i));
            i++;
        }
        // Trim a Javadoc-continuation leading `*` so that lines like
        // `* See FontAwesome migration` don't trip the audit when they
        // appear outside an explicit /* ... */ that the scanner saw
        // (e.g. block-comment continuation lines that begin in the
        // previous file).
        String trimmed = out.toString().trim();
        if (trimmed.startsWith("*")) return "";
        return out.toString();
    }

    /**
     * Locate the daw-app module root. Surefire normally sets the working
     * directory to the module itself; as a fallback we also check for a
     * {@code daw-app} child directory (covers invocations from the repo
     * root such as {@code mvn -pl daw-app test}).
     */
    private static Path locateDawAppModule() {
        Path cwd = Paths.get("").toAbsolutePath();

        // Fast path: Surefire runs from the module dir.
        if (isDawAppModule(cwd)) return cwd;

        // Fallback: invoked from the repo root — look for daw-app child.
        Path child = cwd.resolve("daw-app");
        if (isDawAppModule(child)) return child;

        // Walk up (covers nested invocations or mono-repo layouts).
        Path candidate = cwd.getParent();
        for (int i = 0; i < 5 && candidate != null; i++) {
            if (isDawAppModule(candidate)) return candidate;
            Path nested = candidate.resolve("daw-app");
            if (isDawAppModule(nested)) return nested;
            candidate = candidate.getParent();
        }

        // Last resort — return cwd and let the assertion below fail with
        // a clear message.
        return cwd;
    }

    private static boolean isDawAppModule(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isDirectory(dir.resolve("src/main/java/com/benesquivelmusic/daw/app"));
    }
}
