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
 * Story 293 — the §1.4 / §4.4 re-entrancy-guard removal gate. A SOURCE-scan
 * conformance sentinel: the four hand-rolled cascade guards that single-writer
 * binding makes unnecessary — {@code suppressChangeEvents}, {@code updatingControls},
 * {@code programmaticDimensionUpdate}, {@code updating} — must be gone <em>app-wide</em>
 * across {@code daw-app/.../ui}, replaced by a stateless echo-guard or explicit
 * single-writer listener management ("the guards are deleted, not replaced", §4.4).
 *
 * <p>Through story 301 exactly one guard survived by design:
 * {@code suppressNotification} in the bespoke {@code BinauralMonitorPluginView} —
 * not a control&harr;model cascade guard but a programmatic-vs-user discriminator
 * for an <em>outbound</em> host callback / project-dirty side effect. Story 302
 * deleted that view (the Binaural Monitor is a contract-driven canvas editor in
 * {@code daw-core} now, and the HRTF-profile persistence moved to the profile
 * dialog's close-time result, which needs no discriminator), so the pattern is
 * fully retired: this test now asserts the survivor flag is gone too.</p>
 *
 * <p>Comments and string literals are stripped before matching so a flag named only
 * in a "(was {@code suppressChangeEvents})" Javadoc note does not false-fail; a
 * non-empty-scan guard prevents a broken path from vacuously passing.</p>
 */
final class ReentrancyGuardRemovalTest {

    private static final List<String> REMOVED_GUARDS = List.of(
            "suppressChangeEvents", "updatingControls", "programmaticDimensionUpdate", "updating");

    /** Story 293's justified survivor — retired with its view in story 302. */
    private static final String RETIRED_SURVIVOR_FLAG = "suppressNotification";

    @Test
    void theFourCascadeGuardsAndTheRetiredSurvivorAreGoneAppWide()
            throws IOException {
        Path uiRoot = locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui");
        assertThat(Files.isDirectory(uiRoot))
                .as("daw-app ui sources must live under %s", uiRoot).isTrue();

        List<String> guardOffenders = new ArrayList<>();
        List<String> survivorFiles = new ArrayList<>();
        List<Path> scanned = new ArrayList<>();

        Files.walkFileTree(uiRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                String name = file.getFileName().toString();
                // Skip these very sentinel tests' own siblings — only main sources are scanned
                // (this test lives under src/test, so the walk over src/main never reaches it).
                if (!name.endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                scanned.add(file);
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                String code = stripStringLiterals(stripComments(raw));

                for (String guard : REMOVED_GUARDS) {
                    if (Pattern.compile("\\b" + Pattern.quote(guard) + "\\b").matcher(code).find()) {
                        guardOffenders.add(name + " — references removed guard '" + guard + "'");
                    }
                }
                if (Pattern.compile("\\b" + Pattern.quote(RETIRED_SURVIVOR_FLAG) + "\\b")
                        .matcher(code).find()) {
                    survivorFiles.add(name);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(scanned)
                .as("the daw-app ui source scan must visit a non-trivial number of files")
                .hasSizeGreaterThan(50);

        guardOffenders.sort(String::compareTo);
        assertThat(guardOffenders)
                .as("Story 293 — these four §1.4 re-entrancy guards are made unnecessary by "
                        + "single-writer binding and must be deleted app-wide (§4.4). Offenders:%n  %s",
                        String.join("\n  ", guardOffenders))
                .isEmpty();

        assertThat(survivorFiles)
                .as("Story 302 — story 293's one justified survivor ('%s' in the bespoke "
                        + "BinauralMonitorPluginView) retired with that view; no re-entrancy "
                        + "flag of this family may remain. Found in: %s",
                        RETIRED_SURVIVOR_FLAG, survivorFiles)
                .isEmpty();
    }

    // ── source pre-processing + module location (mirrors RunLaterConsolidationTest) ──

    private static final Pattern COMMENT_OR_STRING = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\""
            + "|\"(?:\\\\.|[^\"\\\\])*\""
            + "|//[^\\n]*"
            + "|/\\*[\\s\\S]*?\\*/");

    private static String stripComments(String source) {
        Matcher m = COMMENT_OR_STRING.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        while (m.find()) {
            String token = m.group();
            m.appendReplacement(out, token.charAt(0) == '"'
                    ? Matcher.quoteReplacement(token) : " ");
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String stripStringLiterals(String code) {
        return code
                .replaceAll("\"\"\"[\\s\\S]*?\"\"\"", "\"\"")
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

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
                && Files.isDirectory(dir.resolve("src/main/java/com/benesquivelmusic/daw/app"));
    }
}
