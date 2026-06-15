package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.ProjectCard;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHealthScanner;
import com.benesquivelmusic.daw.app.ui.hub.WelcomeView;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story-296 {@link WelcomeView} interaction regressions:
 *
 * <ul>
 *   <li>A <b>Continue</b> card actually opens its project on keyboard activation
 *       (it is wired identically for a primary double-click) — without this the
 *       launch screen's recent cards are inert and the user cannot return to
 *       their work.</li>
 *   <li>At most <b>one</b> Recover row carries the default button, so Enter on
 *       the Welcome screen recovers a single, deterministic project rather than
 *       an arbitrary one among several recoverable projects.</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class WelcomeViewInteractionTest {

    /** Lock heartbeat instant; the view's clock is 20 min later (> 10 min stale threshold). */
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempRoot;

    private FxDispatcher dispatcher;

    @BeforeEach
    void installDispatcher() throws Exception {
        dispatcher = new FxDispatcher();
        FxDispatcher.installDefault(dispatcher);
        runOnFxThread(dispatcher::start);
    }

    @AfterEach
    void disposeDispatcher() throws Exception {
        runOnFxThread(dispatcher::dispose);
        FxDispatcher.installDefault(null);
    }

    @Test
    void continueCardOpensItsProjectOnKeyboardActivation() throws Exception {
        Path cleanDir = Files.createDirectory(tempRoot.resolve("clean-session"));
        writeProjectFile(cleanDir, "Clean Song");

        AtomicReference<Path> opened = new AtomicReference<>();
        AtomicReference<WelcomeView> viewRef = new AtomicReference<>();
        runOnFxThread(() -> viewRef.set(new WelcomeView(
                List.of(cleanDir),
                new ProjectHealthScanner(dispatcher),
                dispatcher,
                InstantSource.fixed(T0.plus(20, ChronoUnit.MINUTES)),
                () -> {},               // onNewProject
                opened::set,            // onOpenProject — capture the opened path
                () -> {},               // onOpenFromDisk
                () -> {},               // onRestoreArchive
                () -> {})));            // onImportDawproject

        WelcomeView view = viewRef.get();
        assertThat(view).isNotNull();
        assertThat(view.continuePaths()).containsExactly(cleanDir);
        assertThat(view.continueCards()).hasSize(1);

        // Press Enter on the Continue card: it must open exactly its project.
        runOnFxThread(() -> {
            ProjectCard card = view.continueCards().get(0);
            card.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "",
                    KeyCode.ENTER, false, false, false, false));
        });

        assertThat(opened.get())
                .as("a Continue card is no longer inert — Enter opens its project")
                .isEqualTo(cleanDir);

        joinPendingScans(view);
    }

    @Test
    void atMostOneRecoverRowIsTheDefaultButton() throws Exception {
        Path crashedA = Files.createDirectory(tempRoot.resolve("crashed-a"));
        Path crashedB = Files.createDirectory(tempRoot.resolve("crashed-b"));
        writeProjectFile(crashedA, "Crashed A");
        writeProjectFile(crashedB, "Crashed B");

        // Two STALE locks → both projects are recoverable (two Recover rows).
        ProjectLockManager holderA =
                new ProjectLockManager(InstantSource.fixed(T0), "ben", "studio-pc", 123L);
        ProjectLockManager holderB =
                new ProjectLockManager(InstantSource.fixed(T0), "ben", "studio-pc", 124L);
        holderA.forceAcquire(crashedA);
        holderB.forceAcquire(crashedB);

        InstantSource clock = InstantSource.fixed(T0.plus(20, ChronoUnit.MINUTES));
        AtomicReference<WelcomeView> viewRef = new AtomicReference<>();
        runOnFxThread(() -> viewRef.set(new WelcomeView(
                List.of(crashedA, crashedB),
                new ProjectHealthScanner(dispatcher),
                dispatcher,
                clock,
                () -> {}, _ -> {}, () -> {}, () -> {}, () -> {})));

        WelcomeView view = viewRef.get();
        assertThat(view).isNotNull();
        assertThat(view.recoverPaths()).containsExactly(crashedA, crashedB);

        AtomicReference<Long> defaultCount = new AtomicReference<>();
        runOnFxThread(() -> {
            long defaults = collectButtons(view.recoverSection()).stream()
                    .filter(Button::isDefaultButton)
                    .count();
            defaultCount.set(defaults);
        });

        assertThat(defaultCount.get())
                .as("only one default button across multiple Recover rows")
                .isEqualTo(1L);

        joinPendingScans(view);
        holderA.close();
        holderB.close();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<Button> collectButtons(Node node) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(node, buttons);
        return buttons;
    }

    private static void collectButtons(Node node, List<Button> out) {
        if (node instanceof Button button) {
            out.add(button);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectButtons(child, out);
            }
        }
    }

    private void joinPendingScans(WelcomeView view) throws Exception {
        for (Thread scan : view.pendingScans()) {
            scan.join(2_000);
        }
        runOnFxThread(() -> { /* barrier — let posted scan consumers run */ });
    }

    private static void writeProjectFile(Path dir, String name) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                    <metadata>
                        <name>%s</name>
                        <created-at>2026-01-01T00:00:00Z</created-at>
                        <last-modified>2026-01-01T00:00:00Z</last-modified>
                    </metadata>
                </daw-project>
                """.formatted(name);
        Files.writeString(dir.resolve("project.daw"), xml);
    }

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
