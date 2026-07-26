package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 294 — the §8 Stage 6 / §9 "no callback-up surface {@code Host} for
 * cross-surface updates" gate. A SOURCE-scan conformance sentinel (sibling of
 * {@code HostCallbackRemovalTest} / {@code MainControllerShrinkTest}): it asserts
 * the six migrated surface / lifecycle nested {@code Host} interfaces are gone, and
 * that the <em>only</em> {@code interface Host} declarations left anywhere under the
 * {@code ui} package are the four framework seams plus four pre-existing
 * non-cascade Hosts.
 *
 * <p><strong>On the story wording.</strong> The story says "only the four framework
 * hosts remain", but the {@code ui} package also holds four pre-existing
 * {@code interface Host} declarations the story's "Verified facts" omits:
 * {@code AnimationController} (a single {@code transportState()} read),
 * {@code ArrangementCanvasFactory} (a one-shot factory DI seam), and
 * {@code KeyboardShortcutController} / {@code ViewNavigationController} (§2.8
 * menu/key/click intent routing, which the book <em>endorses</em>). None of these is
 * the cross-surface-UPDATE cascade §4.2/§9 rejects, so they are documented
 * exemptions here, not in scope for this story. The factual contradiction is flagged
 * in the story file; this test enforces §9's real intent — no control-sync-cascade
 * surface {@code Host} survives outside the framework seams.</p>
 *
 * <p>Comments are stripped before matching (so a {@code {@code Host}} Javadoc
 * reference never false-positives), and a non-empty-scan guard prevents a broken
 * path from vacuously passing.</p>
 */
final class NoSurfaceHostInterfaceRemainsTest {

    /** The six surface/lifecycle Hosts story 294 removes (migrated to functional-dep records). */
    private static final List<String> MIGRATED_SURFACE_HOSTS = List.of(
            "PluginViewController.java", "ProjectLifecycleController.java",
            "SnapshotsController.java", "TrackCreationController.java",
            "TrackTemplateController.java", "AudioImportController.java");

    /** The four framework Host seams (relative to the ui root) deliberately retained (Non-Goal). */
    private static final List<String> FRAMEWORK_HOSTS = List.of(
            "dock/DockManager.java", "layout/LayoutManager.java",
            "WorkspaceManager.java", "views/PerformanceStageView.java");

    /**
     * The four NON-cascade Hosts that legitimately retain {@code interface Host}:
     * §2.8 intent routing / factory DI / a single state read — NOT the
     * cross-surface-update cascades §4.2/§9 reject. Documented exemptions (see the
     * class Javadoc); a future story may rename these off "Host" too, but doing so
     * is not in story 294's scope.
     */
    private static final List<String> NON_CASCADE_EXEMPT = List.of(
            "AnimationController.java", "ArrangementCanvasFactory.java",
            "KeyboardShortcutController.java", "ViewNavigationController.java");

    private static final Pattern INTERFACE_HOST = Pattern.compile("\\binterface\\s+Host\\b");

    @Test
    void migratedSurfaceHostsAreGoneAndOnlyFrameworkAndDocumentedNonCascadeHostsRemain()
            throws IOException {
        Path uiRoot = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui");
        assertThat(Files.isDirectory(uiRoot)).as("ui root must exist at %s", uiRoot).isTrue();

        // 1. The six migrated surface/lifecycle Hosts are gone from their files.
        List<String> survivingSurfaceHosts = new ArrayList<>();
        for (String rel : MIGRATED_SURFACE_HOSTS) {
            Path file = uiRoot.resolve(rel);
            assertThat(Files.isRegularFile(file)).as("%s must still exist", rel).isTrue();
            String code = SourceScanSupport.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (INTERFACE_HOST.matcher(code).find()) {
                survivingSurfaceHosts.add(rel);
            }
        }
        assertThat(survivingSurfaceHosts)
                .as("Story 294 §9 — these surface/lifecycle Host interfaces must be gone; their "
                        + "facts now travel as VM bindings / typed bus events. Stragglers:%n  %s",
                        String.join("\n  ", survivingSurfaceHosts))
                .isEmpty();

        // 2. The four framework Host seams are untouched (Non-Goal — never removed).
        List<String> damagedFramework = new ArrayList<>();
        for (String rel : FRAMEWORK_HOSTS) {
            Path file = uiRoot.resolve(rel);
            assertThat(Files.isRegularFile(file)).as("%s must exist", rel).isTrue();
            if (!INTERFACE_HOST.matcher(SourceScanSupport.stripComments(Files.readString(file, StandardCharsets.UTF_8))).find()) {
                damagedFramework.add(rel + " — its framework 'interface Host' was wrongly removed");
            }
        }
        assertThat(damagedFramework)
                .as("Story 294 Non-Goal — the four framework Host seams "
                        + "(Dock/Layout/Workspace/PerformanceStage) must NOT be touched. Damaged:%n  %s",
                        String.join("\n  ", damagedFramework))
                .isEmpty();

        // 3. Whole-ui-package scan: the ONLY files declaring `interface Host` are the
        //    four framework seams + the four documented non-cascade exemptions.
        List<String> allowed = new ArrayList<>();
        allowed.addAll(FRAMEWORK_HOSTS);
        allowed.addAll(NON_CASCADE_EXEMPT);

        int scanned = 0;
        List<String> declaringHostFiles = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(uiRoot)) {
            for (Path file : (Iterable<Path>) tree
                    .filter(p -> p.toString().endsWith(".java"))::iterator) {
                scanned++;
                String code = SourceScanSupport.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (INTERFACE_HOST.matcher(code).find()) {
                    declaringHostFiles.add(uiRoot.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        // Non-empty-scan guard: a truncated/failed walk would make the scan vacuous.
        assertThat(scanned)
                .as("the ui-package walk must visit a non-trivial number of .java files")
                .isGreaterThan(50);

        assertThat(declaringHostFiles)
                .as("Story 294 §9 — the ONLY `interface Host` declarations left under the ui "
                        + "package must be the four framework seams + the four documented "
                        + "non-cascade exemptions (intent routing / factory DI / state read). Any "
                        + "other surface Host is a control-sync-cascade regression.")
                .containsExactlyInAnyOrderElementsOf(allowed);
    }
}
