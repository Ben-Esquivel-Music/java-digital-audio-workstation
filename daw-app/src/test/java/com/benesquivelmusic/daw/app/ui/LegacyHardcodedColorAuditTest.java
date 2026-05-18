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
 * <p>The scan is a SOURCE-level filesystem text scan (the annotation is
 * {@link java.lang.annotation.RetentionPolicy#SOURCE}); no toolkit,
 * module, or reflection is involved. Path resolution mirrors
 * {@code NumericClassAuditTest}.</p>
 */
final class LegacyHardcodedColorAuditTest {

    /** {@code Color.web(} or {@code Color.rgb(} — the hard-coded paint calls. */
    private static final Pattern HARDCODED_COLOR =
            Pattern.compile("\\bColor\\.(web|rgb)\\s*\\(");

    /** Class-level sentinel that records the deferral TODO (story 277). */
    private static final Pattern ANNOTATION =
            Pattern.compile("@HardcodedColorAllowed\\s*\\(");

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
                if (!file.getFileName().toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                scanned.add(file);
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (HARDCODED_COLOR.matcher(source).find()
                        && !ANNOTATION.matcher(source).find()) {
                    offenders.add(uiSrcRoot.relativize(file).toString()
                            .replace('\\', '/'));
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
}
