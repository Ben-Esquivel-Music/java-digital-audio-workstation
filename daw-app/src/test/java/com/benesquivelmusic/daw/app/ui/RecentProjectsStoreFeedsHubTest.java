package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.ProjectCard;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHealthScanner;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHubView;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadataReader;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.persistence.backup.ProjectDiskUsage;

import javafx.application.Platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The story-296 {@code RecentProjectsStoreFeedsHubTest} acceptance criterion:
 * a {@link RecentProjectsStore} feeds the §4.2 {@link ProjectHubView} Recent
 * section — one card per stored path, in the store's most-recent-first order,
 * each carrying its {@link ProjectMetadata} last-opened time and its
 * {@link ProjectDiskUsage} on-disk size.
 *
 * <p>Mirrors {@code HealthScanOffFxThreadTest}: a dedicated, installed
 * {@link FxDispatcher}; FX-thread work funnelled through {@link #runOnFxThread};
 * every value captured inside an FX runnable is stashed in an
 * {@link AtomicReference} and asserted on the test thread (FX swallows an
 * {@code AssertionError} raised on its own thread —
 * {@code feedback_javafx_headless_test_pitfalls}).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RecentProjectsStoreFeedsHubTest {

    @TempDir
    Path tempRoot;

    private FxDispatcher dispatcher;
    private Preferences prefsNode;
    private RecentProjectsStore store;

    @BeforeEach
    void setUp() throws Exception {
        dispatcher = new FxDispatcher();
        FxDispatcher.installDefault(dispatcher);
        runOnFxThread(dispatcher::start);

        // A unique preferences node per test run so concurrent / repeated runs
        // never collide on the platform-native backing store.
        prefsNode = Preferences.userRoot().node("test/hub/" + UUID.randomUUID());
        store = new RecentProjectsStore(prefsNode);
        store.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        runOnFxThread(dispatcher::dispose);
        FxDispatcher.installDefault(null);
        if (prefsNode != null) {
            try {
                prefsNode.removeNode();
            } catch (BackingStoreException | IllegalStateException ignored) {
                // Best-effort cleanup — the node may already be gone.
            }
        }
    }

    @Test
    void recentSectionRendersOneCardPerStoreEntryInOrderWithMetadataAndSize() throws Exception {
        // Three projects with DISTINCT names, last-modified times, and sizes.
        ProjectFixture vienna = writeProject("Live - Vienna",
                Instant.parse("2026-03-12T14:22:00Z"), 1);
        ProjectFixture score = writeProject("Score - Episode 4",
                Instant.parse("2026-03-08T09:00:00Z"), 2);
        ProjectFixture sketches = writeProject("Sketches / Synth",
                Instant.parse("2026-02-19T18:30:00Z"), 3);

        // Add oldest first so the store reports newest first
        // (sketches < score < vienna chronologically; store is most-recent-first).
        store.addRecentProject(sketches.dir());
        store.addRecentProject(score.dir());
        store.addRecentProject(vienna.dir());

        List<Path> recentPaths = store.getRecentProjectPaths();
        assertThat(recentPaths).hasSize(3);

        // Build the Hub on the FX thread; capture it for the test thread.
        AtomicReference<ProjectHubView> hubRef = new AtomicReference<>();
        runOnFxThread(() -> hubRef.set(new ProjectHubView(
                recentPaths,
                new ProjectHealthScanner(dispatcher),
                p -> { },
                p -> { })));
        ProjectHubView hub = hubRef.get();
        assertThat(hub).isNotNull();

        // Join every scan thread (they run off the FX thread on virtual threads),
        // then flush the dispatcher's posted consumers with an FX-thread barrier.
        for (Thread scan : hub.pendingScans()) {
            scan.join(5_000);
            assertThat(scan.isAlive()).as("scan thread should finish").isFalse();
        }
        runOnFxThread(() -> { /* barrier — lets the posted scan consumers run */ });

        // ── assert on the test thread, reading card facts inside an FX barrier ──
        AtomicReference<List<String>> namesRef = new AtomicReference<>();
        AtomicReference<List<Instant>> lastOpenedRef = new AtomicReference<>();
        AtomicReference<List<Long>> sizesRef = new AtomicReference<>();
        runOnFxThread(() -> {
            List<ProjectCard> cards = hub.recentCards();
            List<String> names = new ArrayList<>();
            List<Instant> opened = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            for (ProjectCard card : cards) {
                names.add(card.getName());
                opened.add(card.getLastOpened());
                sizes.add(card.getSizeOnDiskBytes());
            }
            namesRef.set(names);
            lastOpenedRef.set(opened);
            sizesRef.set(sizes);
        });

        // One card per store entry, in the SAME (most-recent-first) order.
        assertThat(namesRef.get())
                .containsExactly("Live - Vienna", "Score - Episode 4", "Sketches / Synth");

        // Each card's last-opened equals that project's ProjectMetadata last-modified.
        List<Instant> expectedOpened = List.of(
                metadataLastModified(vienna.dir()),
                metadataLastModified(score.dir()),
                metadataLastModified(sketches.dir()));
        assertThat(lastOpenedRef.get()).containsExactlyElementsOf(expectedOpened);

        // Each card's size-on-disk equals ProjectDiskUsage.compute(dir).totalBytes().
        List<Long> expectedSizes = List.of(
                diskTotal(vienna.dir()),
                diskTotal(score.dir()),
                diskTotal(sketches.dir()));
        assertThat(sizesRef.get()).containsExactlyElementsOf(expectedSizes);

        // Sizes really do differ (the fixtures wrote distinct checkpoint payloads).
        assertThat(sizesRef.get()).doesNotHaveDuplicates();
    }

    /** A project directory plus its written name / last-modified, for assertions. */
    private record ProjectFixture(Path dir, String name, Instant lastModified) {
    }

    /**
     * Writes a real {@code project.daw} (XML) under {@code tempRoot} with a
     * distinct name and last-modified, plus a {@code checkpoints/} payload of
     * {@code 1024 * sizeUnits} bytes so the three fixtures' sizes differ.
     */
    private ProjectFixture writeProject(String name, Instant lastModified, int sizeUnits)
            throws IOException {
        Path dir = Files.createDirectory(tempRoot.resolve("proj-" + UUID.randomUUID()));
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                    <metadata>
                        <name>%s</name>
                        <created-at>2026-01-01T00:00:00Z</created-at>
                        <last-modified>%s</last-modified>
                    </metadata>
                </daw-project>
                """.formatted(name, lastModified);
        Files.writeString(dir.resolve("project.daw"), xml);

        Path checkpoints = Files.createDirectory(dir.resolve(ProjectDiskUsage.AUTOSAVES_DIR));
        Files.write(checkpoints.resolve("cp.bin"), new byte[1024 * sizeUnits]);
        return new ProjectFixture(dir, name, lastModified);
    }

    private static Instant metadataLastModified(Path dir) {
        ProjectMetadata metadata = ProjectMetadataReader.read(dir)
                .orElseThrow(() -> new AssertionError("no metadata read for " + dir));
        return metadata.lastModified();
    }

    private static long diskTotal(Path dir) throws IOException {
        return ProjectDiskUsage.compute(dir).totalBytes();
    }

    /**
     * Runs {@code action} on the FX thread and blocks until it finishes,
     * re-throwing any {@link Throwable} it raised on the test thread (the
     * capture-and-rethrow helper — an assertion failing on the FX thread is
     * otherwise swallowed).
     */
    private static void runOnFxThread(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for JavaFX action to complete");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }
}
