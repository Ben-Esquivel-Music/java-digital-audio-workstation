package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        Path file = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");
        assertThat(Files.isRegularFile(file))
                .as("MainController source must exist at %s", file).isTrue();

        String raw = Files.readString(file, StandardCharsets.UTF_8);
        // Non-empty-scan guard: MainController is a large file; a truncated/empty
        // read would make every assertion below vacuously pass.
        assertThat(raw.length())
                .as("the MainController source read must be non-trivial").isGreaterThan(50_000);

        String code = SourceScanSupport.stripStringLiterals(
                SourceScanSupport.stripComments(raw));

        List<String> survivingMethods = new ArrayList<>();
        for (String method : RETIRED_REFRESH_METHODS) {
            // Target MainController's OWN method DECLARATIONS — every retired refresh
            // method was a `private void`. (Before story 294, MainController also held
            // no-op `@Override public void updateArrangementPlaceholder()` /
            // `updateUndoRedoState()` / `refreshArrangementCanvas()` for the
            // TrackCreationController / AudioImportController Host interfaces; story 294
            // migrated those surfaces to functional-dep records, so those @Overrides are
            // gone too. Targeting `private void` declarations stays correct regardless.)
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
}
