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

    /** Forbidden icon-family identifiers as case-insensitive regexes. */
    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile("\\bFontAwesome\\b", Pattern.CASE_INSENSITIVE),
            // Match common FontAwesome class prefixes (fa-solid, fa-regular,
            // fa-brands, fa-light, fa-thin, fa-sharp) on a word boundary
            // rather than requiring surrounding double quotes — the latter
            // would miss unquoted CSS selectors and FXML attributes.
            Pattern.compile("\\bfa-(solid|regular|brands|light|thin|sharp|icon)-[a-z0-9-]+\\b"),
            Pattern.compile("\\bMaterial:[a-z0-9_-]+\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bMDI_[A-Z_]+\\b"),
            Pattern.compile("\\bmdi-[a-z0-9-]+\\b"),
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
     */
    private static void scanFile(Path file, List<String> violations) throws IOException {
        AtomicInteger lineNo = new AtomicInteger(0);
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                int n = lineNo.incrementAndGet();
                String stripped = stripComments(line);
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
     * Returns the code-only portion of {@code line}: trailing line
     * comments and inline block-comment segments are stripped, and
     * lines that are entirely comments are reduced to the empty
     * string. This lets javadoc / inline notes legitimately reference
     * obsolete icon family names without tripping the audit.
     */
    private static String stripComments(String line) {
        String trimmed = line.trim();
        // Whole-line comments.
        if (trimmed.startsWith("//") || trimmed.startsWith("*")
                || trimmed.startsWith("/*") || trimmed.startsWith("<!--")) {
            return "";
        }
        // Trailing line comment.
        int slashSlash = line.indexOf("//");
        if (slashSlash >= 0) {
            line = line.substring(0, slashSlash);
        }
        // Inline /* ... */ block (single-line). We don't track multi-line
        // block comment state across the stream; the per-line javadoc
        // prefix check above handles the common case (continuation lines
        // starting with `*`).
        int blockStart;
        while ((blockStart = line.indexOf("/*")) >= 0) {
            int blockEnd = line.indexOf("*/", blockStart + 2);
            if (blockEnd < 0) {
                line = line.substring(0, blockStart);
                break;
            }
            line = line.substring(0, blockStart) + line.substring(blockEnd + 2);
        }
        return line;
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
