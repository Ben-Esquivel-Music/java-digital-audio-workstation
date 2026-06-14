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
 * Story 293 — the §8 Stage 5 "controller becomes a composition root" gate. A
 * SOURCE-scan conformance sentinel (sibling of {@code RunLaterConsolidationTest} /
 * {@code LegacyHardcodedColorAuditTest}): it asserts that {@code MainController}
 * no longer <em>declares</em> any of the retired {@code update*}/{@code refresh*}/
 * {@code sync*} refresh methods (their work is now done by VM bindings — Control
 * Synchronization Design Book §1.1, §4.3) and no longer <em>references</em> any of
 * the removed cascade-feeding {@code Host} interfaces (§4.2, §9).
 *
 * <p>{@code MainController} is never FXML-loaded in tests (it spins up the
 * {@code AudioEngine}, the autosave scheduler and scene listeners and hangs), so
 * this is asserted against the <em>source</em>, not a live instance. Comments and
 * string literals are stripped before matching so a method name surviving only in
 * a "renamed from …" Javadoc note does not false-fail, and a non-empty-scan guard
 * prevents a broken path from vacuously passing.</p>
 */
final class MainControllerShrinkTest {

    /** The dozen §1.1 refresh methods this story deletes (or renames away). */
    private static final List<String> RETIRED_REFRESH_METHODS = List.of(
            "updateTempoDisplay", "updateProjectInfo", "refreshLockStatusIndicator",
            "updateCheckpointStatus", "updateArrangementPlaceholder", "refreshArrangementCanvas",
            "updateUndoRedoState", "updatePlayheadFromTransport", "syncLoopRegionToCanvas",
            "syncSelectionToCanvas");

    /** The cascade-feeding nested {@code Host} interfaces removed by this story. */
    private static final List<String> REMOVED_HOSTS = List.of(
            "TransportController.Host", "TrackStripController.Host", "ClipEditController.Host",
            "ClipInteractionController.Host", "ClipTrimHandler.Host", "ClipFadeHandler.Host",
            "SlipToolHandler.Host", "RippleModeController.Host", "TempoEditController.Host",
            "HistoryPanelController.Host", "DawMenuBarController.Host");

    @Test
    void mainControllerNeitherDeclaresTheRetiredRefreshMethodsNorReferencesARemovedHost()
            throws IOException {
        Path file = locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");
        assertThat(Files.isRegularFile(file))
                .as("MainController source must exist at %s", file).isTrue();

        String raw = Files.readString(file, StandardCharsets.UTF_8);
        // Non-empty-scan guard: MainController is a large file; a truncated/empty
        // read would make every assertion below vacuously pass.
        assertThat(raw.length())
                .as("the MainController source read must be non-trivial").isGreaterThan(50_000);

        String code = stripStringLiterals(stripComments(raw));

        List<String> survivingMethods = new ArrayList<>();
        for (String method : RETIRED_REFRESH_METHODS) {
            // Target MainController's OWN method DECLARATIONS — every retired refresh
            // method was a `private void`. A bare-name scan would false-fail on the
            // no-op `@Override public void updateArrangementPlaceholder()` /
            // `updateUndoRedoState()` / `refreshArrangementCanvas()` that MainController
            // must still provide for the OUT-OF-SCOPE TrackCreationController.Host /
            // AudioImportController.Host interfaces (those move in story 294); those are
            // not god-controller refresh methods and are correctly redirected/no-op'd.
            if (Pattern.compile("\\bprivate\\s+void\\s+" + Pattern.quote(method) + "\\s*\\(")
                    .matcher(code).find()) {
                survivingMethods.add(method);
            }
        }
        assertThat(survivingMethods)
                .as("Story 293 — MainController must no longer DECLARE these refresh methods "
                        + "(their work is now a VM binding/subscription, §4.3). Still declared:%n  %s",
                        String.join("\n  ", survivingMethods))
                .isEmpty();

        List<String> survivingHosts = new ArrayList<>();
        for (String host : REMOVED_HOSTS) {
            if (code.contains(host)) {
                survivingHosts.add(host);
            }
        }
        assertThat(survivingHosts)
                .as("Story 293 — MainController must not reference any removed cascade-feeding "
                        + "Host interface (§4.2/§9 — facts now travel as typed events / VM bindings). "
                        + "Still referenced:%n  %s", String.join("\n  ", survivingHosts))
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
