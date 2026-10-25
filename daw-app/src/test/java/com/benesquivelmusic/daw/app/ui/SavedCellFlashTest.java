package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress.NamedOperation;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress.SaveScope;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStripSkin;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell;

import javafx.application.Platform;
import javafx.collections.SetChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331 — the Saved cell's one flash on a head save success (PM book
 * §4.5, rulings G4 (c) and L2): the cell carries the
 * {@link StatusStripCell#FLASH_PSEUDO_CLASS} pseudo-class for
 * {@link SessionStatusStrip#SAVED_FLASH_DURATION}, once per success, whatever
 * the cell shows — the amber checkpoint failure and another operation
 * included. Nothing flashes under Reduce Motion (the new seconds carry the
 * signal, in every state), nor while the strip's window is not showing, nor
 * for a checkpoint, a failure or a project replacement; disposing the skin
 * stops a flash, and the disposed skin no longer listens to the model.
 *
 * <p>The strip is shown in a headless {@code Stage}. Every change of the
 * cell's flash pseudo-class is recorded on the FX thread, as it happens, with
 * its time; the tests read that record — never the live pseudo-class after a
 * round trip that would have to finish inside the flash. Reduce Motion is a
 * {@link MotionManager} of the test's own, over a preferences node removed
 * afterwards. Every time is a fixed local time of a fixed day, and the
 * model's today starts from a fixed clock (ruling L1).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SavedCellFlashTest {

    private static final PseudoClass FLASH = PseudoClass.getPseudoClass(StatusStripCell.FLASH_PSEUDO_CLASS);
    /** The bound of every wait for a flash to end. */
    private static final Duration GUARD = Duration.ofSeconds(5);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private static final Instant SAVED = TODAY.atTime(14, 35, 7).atZone(ZONE).toInstant();
    private static final Instant LAST_GOOD = TODAY.atTime(14, 32, 0).atZone(ZONE).toInstant();
    private static final Path CHECKPOINT = Path.of("Song A", "checkpoints", "checkpoint-001-20261023T143200.daw");

    /** Never started: the model's keyed facts apply only in {@link #pulse()}. */
    private final FxDispatcher dispatcher = new FxDispatcher();
    private final ProjectOperationProgress progress = new ProjectOperationProgress(dispatcher,
            Clock.fixed(TODAY.atTime(12, 0).atZone(ZONE).toInstant(), ZONE));
    /** Each change of the Saved cell's flash: {@code true} when it began, {@code false} when it ended. */
    private final List<FlashChange> changes = new CopyOnWriteArrayList<>();

    private Preferences motionNode;
    private MotionManager motion;
    private Stage stage;
    private SessionStatusStrip strip;

    private record FlashChange(boolean on, long nanos) { }

    /** When a pulse began and ended on the FX thread (nanoTime). */
    private record Pulse(long startNanos, long endNanos) { }

    @BeforeEach
    void showTheStrip() throws Exception {
        motionNode = Preferences.userRoot().node("savedCellFlashTest_" + System.nanoTime());
        motion = new MotionManager(motionNode, Optional::empty);
        motion.setReduceMotion(false);
        runOnFx(() -> {
            strip = new SessionStatusStrip(progress, motion);
            stage = new Stage();
            stage.setScene(new Scene(new Group(strip), 1400, 80));
            stage.show();
            strip.applyCss();
            strip.layout();
            watchTheFlashOf(strip.savedCell());
        });
    }

    @AfterEach
    void hideTheStrip() throws Exception {
        try {
            runOnFx(() -> stage.hide());
        } finally {
            motionNode.removeNode();
        }
    }

    @Test
    void aHeadSaveSuccessFlashesTheCellOnceForSixHundredMilliseconds() throws Exception {
        assertThat(SessionStatusStrip.SAVED_FLASH_DURATION).isEqualTo(javafx.util.Duration.millis(600));

        progress.recordSaveSucceeded(progress.projectGeneration(), SAVED, SaveScope.FULL);
        Pulse pulse = timedPulse();

        assertThat(changes).as("the flash begins in the frame the success applies in").first().satisfies(change -> {
            assertThat(change.on()).isTrue();
            assertThat(change.nanos()).isBetween(pulse.startNanos(), pulse.endNanos());
        });
        awaitFlashChanges(2);
        assertThat(changes).extracting(FlashChange::on).as("one flash: on, then off").containsExactly(true, false);
        Duration lit = Duration.ofNanos(changes.get(1).nanos() - changes.get(0).nanos());
        // From below: the 600 ms, less 50 ms of slack for the animation timer's alignment (about
        // three 60 Hz frames).
        // From above: the timeline ends on the first FX pulse at or after 600 ms, so on an
        // idle machine it lasts 600-617 ms; the bound leaves another 600 ms for a loaded
        // build machine to run that pulse late, yet fails a flash twice its length.
        assertThat(lit).as("it lasts its 600 ms").isGreaterThanOrEqualTo(Duration.ofMillis(550))
                .isLessThan(Duration.ofMillis(1200));
        assertThat(onFx(() -> strip.savedCell().getText())).isEqualTo("✓ Saved 14:35:07");
    }

    @Test
    void aHeadSaveSuccessFlashesTheAmberCellAndTheCellOfAnotherOperationToo() throws Exception {
        long generation = progress.projectGeneration();
        progress.recordCheckpointsSeeded(LAST_GOOD, CHECKPOINT, () -> true);
        progress.recordCheckpointFailed(generation, LAST_GOOD, new IOException("checkpoints is read-only"));
        pulse();

        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        pulse();
        assertThat(changes).extracting(FlashChange::on).as("the amber cell flashes for the head save").startsWith(true);
        assertThat(onFx(() -> strip.savedCell().getText()))
                .isEqualTo("⚠ Checkpoint failed · last good 14:32 · saved 14:35:07");
        awaitFlashChanges(2);

        progress.recordCheckpointWritten(generation, LAST_GOOD, CHECKPOINT, () -> true);
        progress.addOperation(new NamedOperation("Archiving project…"));
        pulse();
        assertThat(changes).as("a checkpoint success and an operation do not flash").hasSize(2);
        progress.recordSaveSucceeded(generation, TODAY.atTime(14, 36, 41).atZone(ZONE).toInstant(), SaveScope.FULL);
        pulse();
        assertThat(changes).extracting(FlashChange::on).as("the operation's cell flashes for the head save")
                .startsWith(true, false, true);
        assertThat(onFx(() -> strip.savedCell().getText())).isEqualTo("◐ Archiving project… · saved 14:36:41");
    }

    @Test
    void underReduceMotionNothingFlashesAndTheSecondsCarryTheSignal() throws Exception {
        runOnFx(() -> motion.setReduceMotion(true));
        long generation = progress.projectGeneration();

        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        pulse();
        assertThat(onFx(() -> strip.savedCell().getText()))
                .as("the time with seconds is the signal").isEqualTo("✓ Saved 14:35:07");

        progress.recordCheckpointsSeeded(LAST_GOOD, CHECKPOINT, () -> true);
        progress.recordCheckpointFailed(generation, LAST_GOOD, new IOException("checkpoints is read-only"));
        pulse();
        progress.recordSaveSucceeded(generation, TODAY.atTime(14, 36, 41).atZone(ZONE).toInstant(), SaveScope.FULL);
        pulse();
        assertThat(onFx(() -> strip.savedCell().getText()))
                .as("on the amber cell too (ruling L2)")
                .isEqualTo("⚠ Checkpoint failed · last good 14:32 · saved 14:36:41");
        runOnFx(() -> { });

        assertThat(changes).as("no flash under Reduce Motion").isEmpty();
    }

    @Test
    void nothingFlashesWhileTheStripsWindowIsNotShowing() throws Exception {
        runOnFx(() -> stage.hide());
        assertThat(onFx(() -> strip.getScene())).as("a hidden window keeps its scene").isNotNull();

        progress.recordSaveSucceeded(progress.projectGeneration(), SAVED, SaveScope.FULL);
        pulse();
        runOnFx(() -> { });

        assertThat(changes).as("no flash for a window nobody sees").isEmpty();
    }

    @Test
    void aCheckpointAFailureOrAProjectReplacementNeverFlashes() throws Exception {
        long generation = progress.projectGeneration();

        progress.recordCheckpointsSeeded(LAST_GOOD, CHECKPOINT, () -> true);
        progress.recordCheckpointWritten(generation, LAST_GOOD, CHECKPOINT, () -> true);
        pulse();
        progress.recordCheckpointFailed(generation, SAVED, new IOException("checkpoint refused"));
        progress.recordSaveFailed(generation, SAVED, new IOException("save refused"));
        pulse();
        runOnFx(() -> progress.resetForProject(SAVED));
        pulse();
        runOnFx(() -> { });
        assertThat(changes).as("only a head save success flashes").isEmpty();

        progress.recordSaveSucceeded(progress.projectGeneration(), SAVED, SaveScope.FULL);
        pulse();
        assertThat(changes).extracting(FlashChange::on).as("and this fixture does flash for one: on, then at most off")
                .startsWith(true).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void disposingTheSkinStopsTheFlashAndTheDisposedSkinNoLongerListensToTheModel() throws Exception {
        long generation = progress.projectGeneration();
        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        StatusStripCell disposedCell = onFx(strip::savedCell);
        boolean[] lit = new boolean[2];
        runOnFx(() -> {
            // In one FX step, so the flash cannot end on its own in between.
            dispatcher.pulse();
            lit[0] = disposedCell.getPseudoClassStates().contains(FLASH);
            strip.setSkin(new SessionStatusStripSkin(strip));
            lit[1] = disposedCell.getPseudoClassStates().contains(FLASH);
        });
        assertThat(lit[0]).as("fixture: the success lit the cell").isTrue();
        assertThat(lit[1]).as("dispose ends the flash at once").isFalse();
        String disposedText = onFx(disposedCell::getText);
        String disposedTooltip = onFx(() -> disposedCell.getTooltip().getText());

        // A listener the disposed skin left on the model would run in this pulse and
        // reach a skin with no control: JavaFX hands what it throws to the FX
        // thread's uncaught-exception handler, which the test captures.
        List<Throwable> uncaught = new CopyOnWriteArrayList<>();
        AtomicReference<Thread.UncaughtExceptionHandler> previous = new AtomicReference<>();
        runOnFx(() -> {
            previous.set(Thread.currentThread().getUncaughtExceptionHandler());
            Thread.currentThread().setUncaughtExceptionHandler((_, failure) -> uncaught.add(failure));
        });
        try {
            progress.recordSaveSucceeded(generation, TODAY.atTime(14, 40, 0).atZone(ZONE).toInstant(),
                    SaveScope.FULL);
            progress.setToday(TODAY.plusDays(1));
            pulse();
            runOnFx(() -> { });
        } finally {
            runOnFx(() -> Thread.currentThread().setUncaughtExceptionHandler(previous.get()));
        }

        assertThat(uncaught).as("no listener of the disposed skin ran").isEmpty();
        assertThat(changes).extracting(FlashChange::on).as("the disposed cell never flashed again")
                .containsExactly(true, false);
        assertThat(onFx(disposedCell::getText)).as("nor follows the model's facts, today included")
                .isEqualTo(disposedText).isEqualTo("✓ Saved 14:35:07");
        assertThat(onFx(() -> disposedCell.getTooltip().getText())).isEqualTo(disposedTooltip);
        assertThat(onFx(() -> strip.savedCell().getText())).as("the new skin's cell does")
                .isEqualTo("✓ Saved 2026-10-23 14:40:00");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Records each change of {@code cell}'s flash pseudo-class with its time, as it happens. FX thread. */
    private void watchTheFlashOf(StatusStripCell cell) {
        cell.getPseudoClassStates().addListener((SetChangeListener<PseudoClass>) change -> {
            if (FLASH.equals(change.getElementAdded())) {
                changes.add(new FlashChange(true, System.nanoTime()));
            }
            if (FLASH.equals(change.getElementRemoved())) {
                changes.add(new FlashChange(false, System.nanoTime()));
            }
        });
    }

    /** Polls the record, bounded by {@link #GUARD}, until it holds {@code count} changes. */
    private void awaitFlashChanges(int count) throws Exception {
        long deadline = System.nanoTime() + GUARD.toNanos();
        while (changes.size() < count) {
            assertThat(System.nanoTime() - deadline < 0).as("%s flash changes within %s", count, GUARD).isTrue();
            Thread.sleep(20);
        }
    }

    /** One pulse of the model's dispatcher, after every FX post made before it. */
    private void pulse() throws Exception {
        runOnFx(dispatcher::pulse);
    }

    /** {@link #pulse()}, stamped on the FX thread where it begins and ends. */
    private Pulse timedPulse() throws Exception {
        AtomicReference<Pulse> stamped = new AtomicReference<>();
        runOnFx(() -> {
            long start = System.nanoTime();
            dispatcher.pulse();
            stamped.set(new Pulse(start, System.nanoTime()));
        });
        return stamped.get();
    }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFx(() -> result.set(action.get()));
        return result.get();
    }

    /** Runs {@code action} on the FX thread and rethrows what it threw; bounded at 5 s. */
    private static void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).as("the FX action returned within 5 s").isTrue();
        if (thrown.get() instanceof Error error) {
            throw error;
        }
        if (thrown.get() != null) {
            throw new AssertionError("the FX action threw", thrown.get());
        }
    }
}
