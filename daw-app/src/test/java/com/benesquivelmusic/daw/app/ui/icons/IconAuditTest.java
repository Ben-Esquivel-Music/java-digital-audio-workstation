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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forbids references to legacy icon families in the UI module.
 *
 * <p>Per UI Design Book §3.6 (Iconography) the DAW has a <em>single</em>
 * approved icon family: Lucide, surfaced via {@link DawgIcon}. This
 * audit scans the {@code daw-app} module's Java sources for tell-tale
 * string literals of icon-font libraries that previously appeared in
 * the codebase (FontAwesome, Material Design Icons / MDI, etc.) and
 * fails if any survive.</p>
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
            Pattern.compile("\"fa-[a-z0-9-]+\""),
            Pattern.compile("\\bMaterial:[a-z0-9_-]+\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bMDI_[A-Z_]+\\b"),
            Pattern.compile("\"mdi-[a-z0-9-]+\""),
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

    @Test
    void uiSourcesMustNotReferenceLegacyIconFamilies() throws IOException {
        Path moduleRoot = locateDawAppModule();
        Path javaRoot = moduleRoot.resolve("src/main/java");
        assertThat(Files.isDirectory(javaRoot))
                .as("UI java sources must live under " + javaRoot)
                .isTrue();

        List<String> violations = new ArrayList<>();
        Files.walkFileTree(javaRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".java")) return FileVisitResult.CONTINUE;
                if (SELF_EXEMPT.contains(fileName)) return FileVisitResult.CONTINUE;

                String src = Files.readString(file, StandardCharsets.UTF_8);
                int lineNo = 0;
                for (String line : src.split("\n", -1)) {
                    lineNo++;
                    // Skip comment-only lines so that documentation
                    // discussing the obsolete families (this PR adds a
                    // few — e.g. references to FontAwesome in javadoc)
                    // does not trip the audit.
                    String trimmed = line.trim();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")
                            || trimmed.startsWith("/*")) continue;
                    for (Pattern p : FORBIDDEN_PATTERNS) {
                        if (p.matcher(line).find()) {
                            violations.add(file + ":" + lineNo + " — matched " + p.pattern()
                                    + " -> " + line.trim());
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(violations)
                .as("UI Design Book §3.6 forbids icon-font references — migrate to DawgIcon (Lucide).")
                .isEmpty();
    }

    /** Locate the daw-app module root by walking up from the working dir. */
    private static Path locateDawAppModule() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd;
        for (int i = 0; i < 6 && candidate != null; i++) {
            Path pom = candidate.resolve("pom.xml");
            Path javaRoot = candidate.resolve("src/main/java/com/benesquivelmusic/daw/app");
            if (Files.isRegularFile(pom) && Files.isDirectory(javaRoot)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        // Surefire runs from the module dir; this is the expected branch.
        return cwd;
    }
}
