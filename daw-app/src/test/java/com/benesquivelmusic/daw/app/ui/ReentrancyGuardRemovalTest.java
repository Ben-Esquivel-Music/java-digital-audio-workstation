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
 * <p>Exactly one guard survives by design: {@code suppressNotification} in
 * {@link BinauralMonitorPluginView}, which is NOT a control&harr;model cascade guard
 * but a programmatic-vs-user discriminator for an <em>outbound</em> host callback /
 * project-dirty side effect (the story's "where a flag has a genuinely live
 * non-cascade use, flag it and keep narrowly"). The test asserts it is the only
 * surviving flag and that its file documents the justification.</p>
 *
 * <p>Comments and string literals are stripped before matching so a flag named only
 * in a "(was {@code suppressChangeEvents})" Javadoc note does not false-fail; a
 * non-empty-scan guard prevents a broken path from vacuously passing.</p>
 */
final class ReentrancyGuardRemovalTest {

    private static final List<String> REMOVED_GUARDS = List.of(
            "suppressChangeEvents", "updatingControls", "programmaticDimensionUpdate", "updating");

    private static final String SURVIVOR_FLAG = "suppressNotification";
    private static final String SURVIVOR_FILE = "BinauralMonitorPluginView.java";

    @Test
    void theFourCascadeGuardsAreGoneAppWideAndOnlyTheJustifiedSurvivorRemains()
            throws IOException {
        Path uiRoot = locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui");
        assertThat(Files.isDirectory(uiRoot))
                .as("daw-app ui sources must live under %s", uiRoot).isTrue();

        List<String> guardOffenders = new ArrayList<>();
        List<String> survivorFiles = new ArrayList<>();
        List<String> survivorJustificationGaps = new ArrayList<>();
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
                if (Pattern.compile("\\b" + Pattern.quote(SURVIVOR_FLAG) + "\\b").matcher(code).find()) {
                    survivorFiles.add(name);
                    // The survivor must carry an explicit justification in its source
                    // (a SOURCE-level marker, checked against the RAW text incl. comments).
                    if (!raw.contains("justified survivor")) {
                        survivorJustificationGaps.add(name);
                    }
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
                .as("Story 293 — the only surviving re-entrancy flag must be the justified "
                        + "'%s' in %s (an outbound-side-effect discriminator, not a self-cascade). "
                        + "Found in: %s", SURVIVOR_FLAG, SURVIVOR_FILE, survivorFiles)
                .containsExactly(SURVIVOR_FILE);

        assertThat(survivorJustificationGaps)
                .as("Story 293 — the surviving '%s' flag must document why it is kept "
                        + "(a 'justified survivor' note in its source).", SURVIVOR_FLAG)
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
