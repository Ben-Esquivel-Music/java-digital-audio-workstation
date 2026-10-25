package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress.NamedOperation;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress.SaveScope;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell.Severity;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.scene.Group;
import javafx.scene.Scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331 — the Saved cell of the Session Status Strip (PM book §4.5,
 * rulings G4, K1, L1 and L2), as the strip renders the model's facts: the
 * text is the first of five that holds (a head save running past 500 ms, a
 * head failure, a checkpoint failure, another operation, the last save); the
 * checkpoint failure and the other operation also show the last head save's
 * time; the severity is decided apart from the text (CRITICAL while a head
 * failure stands, WARN while only a checkpoint failure does); a time of
 * another day than the model's today carries its date, and gains it when
 * only today moves on past midnight; a checkpoint shows only in the tooltip;
 * each failure clears only as G4 (d) says; the tooltip names each failure's
 * cause, the last save and the last good checkpoint; the accessible text
 * says the state in words.
 *
 * <p>Every time is a fixed local time of a fixed day, and the model's today
 * starts from a fixed clock (ruling L1: the skin reads no clock), so no
 * expectation depends on when the test runs — the minutes after midnight
 * included. The strip is realized headlessly in a {@code Scene}; the model's
 * keyed facts apply only in a {@link FxDispatcher#pulse() pulse} of its own,
 * never-started dispatcher. Every FX read is captured and rethrown on the
 * test thread. The flash is {@code SavedCellFlashTest}'s.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SavedCellTextAndSeverityTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    /** The day the tests run "on", whatever the wall clock says. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    /** Ruling L2's example: the head save, and the last good checkpoint before it. */
    private static final Instant SAVED = at(TODAY, 14, 35, 7);
    private static final Instant LAST_GOOD = at(TODAY, 14, 32, 0);

    /** Ruling K1's example of the last good checkpoint. */
    private static final Path CHECKPOINT =
            Path.of("Song A", "checkpoints", "checkpoint-047-20261022T181000.daw");
    private static final IOException SAVE_FAILURE = new IOException("There is not enough space on the disk");
    private static final IOException CHECKPOINT_FAILURE = new IOException("checkpoints is read-only");

    /** Never started: the model's keyed facts apply only in {@link #pulse()}. */
    private final FxDispatcher dispatcher = new FxDispatcher();
    private ProjectOperationProgress progress = modelOn(TODAY, LocalTime.NOON);
    private SessionStatusStrip strip;

    /** What the Saved cell shows, read on the FX thread. */
    private record Cell(String text, Severity severity, String tooltip, String accessibleText,
                        String accessibleHelp) { }

    @Test
    void theTextIsTheFirstOfFiveThatHoldsAndTheSeverityIsDecidedApartFromIt() throws Exception {
        realizeTheStrip();
        long generation = progress.projectGeneration();
        NamedOperation archiving = new NamedOperation("Archiving project…");
        NamedOperation saving = new NamedOperation(ProjectOperationProgress.HEAD_SAVE_OPERATION_NAME);
        assertThat(cell()).returns("Not saved yet", Cell::text).returns(Severity.NORMAL, Cell::severity);

        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        progress.recordCheckpointsSeeded(LAST_GOOD, CHECKPOINT, () -> true);
        progress.addOperation(archiving);
        pulse();
        progress.recordCheckpointFailed(generation, SAVED, CHECKPOINT_FAILURE);
        progress.recordSaveFailed(generation, SAVED, SAVE_FAILURE);
        progress.addOperation(saving);
        pulse();
        assertThat(cell()).as("1. a head save running past 500 ms, though both failures stand: still red")
                .returns("◐ Saving project…", Cell::text).returns(Severity.CRITICAL, Cell::severity);

        progress.removeOperation(saving);
        pulse();
        assertThat(cell()).as("2. the head failure")
                .returns("⚠ Save failed · last saved 14:35", Cell::text)
                .returns(Severity.CRITICAL, Cell::severity);

        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        pulse();
        assertThat(cell()).as("3. the checkpoint failure, and the head save's time (ruling L2)")
                .returns("⚠ Checkpoint failed · last good 14:32 · saved 14:35:07", Cell::text)
                .returns(Severity.WARN, Cell::severity);

        progress.recordCheckpointWritten(generation, LAST_GOOD, CHECKPOINT, () -> true);
        pulse();
        assertThat(cell()).as("4. another running operation, and the head save's time (ruling L2)")
                .returns("◐ Archiving project… · saved 14:35:07", Cell::text)
                .returns(Severity.NORMAL, Cell::severity);

        progress.removeOperation(archiving);
        pulse();
        assertThat(cell()).as("5. the last save, with seconds")
                .returns("✓ Saved 14:35:07", Cell::text)
                .returns(Severity.NORMAL, Cell::severity);
    }

    @Test
    void theCheckpointFailureAndAnotherOperationShowTheHeadSavesTimeOnlyWhenThereIsOne() throws Exception {
        realizeTheStrip();
        long generation = progress.projectGeneration();
        NamedOperation archiving = new NamedOperation("Archiving project…");

        progress.addOperation(archiving);
        pulse();
        assertThat(cell().text()).as("never saved: the operation alone").isEqualTo("◐ Archiving project…");

        progress.recordCheckpointFailed(generation, SAVED, CHECKPOINT_FAILURE);
        pulse();
        assertThat(cell()).as("never saved, no good checkpoint: the failure alone")
                .returns("⚠ Checkpoint failed", Cell::text).returns(Severity.WARN, Cell::severity);
        String tooltipNeverSaved = cell().tooltip();

        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        pulse();
        assertThat(cell()).as("a head save while the failure stands: its seconds show, the severity stays")
                .returns("⚠ Checkpoint failed · saved 14:35:07", Cell::text)
                .returns(Severity.WARN, Cell::severity)
                .returns("Warning: Checkpoint failed, saved 14:35:07", Cell::accessibleText);
        assertThat(cell().tooltip()).as("the tooltip is unchanged but for the last save's own line")
                .isEqualTo(tooltipNeverSaved.replace("Not saved yet", "Last saved 14:35:07 (full)"));

        Instant later = at(TODAY, 14, 36, 41);
        progress.recordSaveSucceeded(generation, later, SaveScope.FULL);
        pulse();
        assertThat(cell().text()).as("each Ctrl+S changes the amber cell's text")
                .isEqualTo("⚠ Checkpoint failed · saved 14:36:41");

        progress.recordCheckpointWritten(generation, LAST_GOOD, CHECKPOINT, () -> true);
        pulse();
        assertThat(cell().text()).isEqualTo("◐ Archiving project… · saved 14:36:41");
    }

    @Test
    void aTimeOfAnotherDayCarriesItsDateFirstOnTheCellAndInTheTooltip() throws Exception {
        realizeTheStrip();
        Instant earlier = at(TODAY.minusDays(3), 14, 22, 5);
        long generation = progress.projectGeneration();

        progress.recordSaveSucceeded(generation, earlier, SaveScope.FULL);
        progress.recordCheckpointsSeeded(earlier, CHECKPOINT, () -> true);
        pulse();
        assertThat(cell().text()).isEqualTo("✓ Saved 2026-10-20 14:22:05");
        assertThat(cell().tooltip()).contains(
                "Last saved 2026-10-20 14:22:05 (full)",
                "Last checkpoint 2026-10-20 14:22:05 · checkpoint-047-20261022T181000.daw");

        progress.recordCheckpointFailed(generation, at(TODAY, 14, 40, 0), CHECKPOINT_FAILURE);
        pulse();
        assertThat(cell().text())
                .isEqualTo("⚠ Checkpoint failed · last good 2026-10-20 14:22 · saved 2026-10-20 14:22:05");

        progress.recordSaveFailed(generation, at(TODAY, 14, 41, 0), SAVE_FAILURE);
        pulse();
        assertThat(cell().text()).isEqualTo("⚠ Save failed · last saved 2026-10-20 14:22");
    }

    @Test
    void aTimeOfTheDayBeforeGainsItsDateWhenOnlyTodayMovesOnPastMidnight() throws Exception {
        // The strip as it stood at 23:59:59: the model's clock is pinned there.
        progress = modelOn(TODAY, LocalTime.of(23, 59, 59));
        realizeTheStrip();
        long generation = progress.projectGeneration();
        progress.recordSaveSucceeded(generation, at(TODAY, 23, 58, 1), SaveScope.FULL);
        progress.recordCheckpointsSeeded(at(TODAY, 23, 55, 0), CHECKPOINT, () -> true);
        pulse();
        assertThat(cell().text()).isEqualTo("✓ Saved 23:58:01");
        assertThat(cell().tooltip()).isEqualTo(String.join("\n",
                "Last saved 23:58:01 (full)",
                "Last checkpoint 23:55:00 · checkpoint-047-20261022T181000.daw"));

        // The strip's poll after midnight: nothing but today changes.
        progress.setToday(TODAY.plusDays(1));
        pulse();

        assertThat(cell().text()).as("ruling L1's example").isEqualTo("✓ Saved 2026-10-23 23:58:01");
        assertThat(cell().tooltip()).isEqualTo(String.join("\n",
                "Last saved 2026-10-23 23:58:01 (full)",
                "Last checkpoint 2026-10-23 23:55:00 · checkpoint-047-20261022T181000.daw"));
    }

    @Test
    void inTheMinutesAfterMidnightEveryTimeOfTheDayBeforeCarriesItsDateAndOneOfTodayNone() throws Exception {
        // The strip at 00:00:30: the model's clock is pinned there, so its today is the new day.
        LocalDate tomorrow = TODAY.plusDays(1);
        progress = modelOn(tomorrow, LocalTime.of(0, 0, 30));
        realizeTheStrip();
        long generation = progress.projectGeneration();
        progress.recordSaveSucceeded(generation, at(TODAY, 23, 58, 30), SaveScope.FULL);
        progress.recordCheckpointsSeeded(at(TODAY, 23, 59, 0), CHECKPOINT, () -> true);
        pulse();
        assertThat(cell().text()).isEqualTo("✓ Saved 2026-10-23 23:58:30");

        progress.recordCheckpointFailed(generation, at(tomorrow, 0, 0, 10), CHECKPOINT_FAILURE);
        pulse();
        assertThat(cell().text())
                .isEqualTo("⚠ Checkpoint failed · last good 2026-10-23 23:59 · saved 2026-10-23 23:58:30");
        assertThat(cell().tooltip()).startsWith("Checkpoint failed at 00:00:10: checkpoints is read-only\n");

        progress.recordSaveFailed(generation, at(tomorrow, 0, 0, 20), SAVE_FAILURE);
        pulse();
        assertThat(cell().text()).isEqualTo("⚠ Save failed · last saved 2026-10-23 23:58");

        progress.recordSaveSucceeded(generation, at(tomorrow, 0, 0, 30), SaveScope.FULL);
        progress.recordCheckpointWritten(generation, at(tomorrow, 0, 0, 30), CHECKPOINT, () -> true);
        pulse();
        assertThat(cell().text()).as("a time of today carries no date").isEqualTo("✓ Saved 00:00:30");
    }

    @Test
    void todayIsPostedAndAppliedOnlyWhenTheDateChanges() throws Exception {
        realizeTheStrip();
        AtomicInteger todayChanges = new AtomicInteger();
        InvalidationListener counted = _ -> todayChanges.incrementAndGet();
        runOnFx(() -> progress.todayProperty().addListener(counted));
        try {
            progress.setToday(LocalDate.of(2026, 10, 23));
            pulse();
            assertThat(todayChanges).as("an equal date, another instance: nothing changes").hasValue(0);

            progress.setToday(TODAY.plusDays(1));
            pulse();
            progress.setToday(LocalDate.of(2026, 10, 24));
            pulse();
            assertThat(todayChanges).as("one change for a new day polled twice").hasValue(1);
            assertThat(onFx(progress::getToday)).isEqualTo(TODAY.plusDays(1));
        } finally {
            runOnFx(() -> progress.todayProperty().removeListener(counted));
        }
    }

    @Test
    void aCheckpointFailureWithNoGoodCheckpointKnownShowsTheFailureAlone() throws Exception {
        realizeTheStrip();
        Instant failed = at(TODAY, 14, 40, 12);

        progress.recordCheckpointFailed(progress.projectGeneration(), failed, CHECKPOINT_FAILURE);
        pulse();

        assertThat(cell()).returns("⚠ Checkpoint failed", Cell::text).returns(Severity.WARN, Cell::severity);
        assertThat(cell().tooltip()).isEqualTo(String.join("\n",
                "Checkpoint failed at 14:40:12: checkpoints is read-only",
                "Not saved yet",
                "No earlier checkpoint of this project was found."));
    }

    @Test
    void aCheckpointSuccessNeverChangesTheTextAndShowsOnlyInTheTooltip() throws Exception {
        realizeTheStrip();
        Instant checkpointed = at(TODAY, 14, 35, 8);
        long generation = progress.projectGeneration();
        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        pulse();
        Cell before = cell();

        progress.recordCheckpointWritten(generation, checkpointed, CHECKPOINT, () -> true);
        pulse();

        assertThat(cell().text()).as("the time stays the last head save's").isEqualTo(before.text())
                .isEqualTo("✓ Saved 14:35:07");
        assertThat(cell().severity()).isEqualTo(Severity.NORMAL);
        assertThat(cell().tooltip()).isEqualTo(String.join("\n",
                "Last saved 14:35:07 (full)",
                "Last checkpoint 14:35:08 · checkpoint-047-20261022T181000.daw"));
    }

    @Test
    void eachFailureClearsOnlyOnASuccessOfItsOwnKindOrAProjectReplacement() throws Exception {
        realizeTheStrip();
        long generation = progress.projectGeneration();
        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        progress.recordSaveFailed(generation, SAVED, SAVE_FAILURE);
        pulse();
        progress.recordCheckpointWritten(generation, LAST_GOOD, CHECKPOINT, () -> true);
        pulse();
        assertThat(cell()).as("a checkpoint success never clears a head failure")
                .returns(Severity.CRITICAL, Cell::severity)
                .returns("⚠ Save failed · last saved 14:35", Cell::text);
        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        pulse();
        assertThat(cell()).as("a head save success does").returns(Severity.NORMAL, Cell::severity);

        progress.recordCheckpointFailed(generation, SAVED, CHECKPOINT_FAILURE);
        pulse();
        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        pulse();
        assertThat(cell()).as("a head save success never clears a checkpoint failure")
                .returns(Severity.WARN, Cell::severity)
                .returns("⚠ Checkpoint failed · last good 14:32 · saved 14:35:07", Cell::text);
        progress.recordCheckpointWritten(generation, LAST_GOOD, CHECKPOINT, () -> true);
        pulse();
        assertThat(cell()).as("a checkpoint success does").returns(Severity.NORMAL, Cell::severity);

        progress.recordCheckpointFailed(generation, SAVED, CHECKPOINT_FAILURE);
        progress.recordSaveFailed(generation, SAVED, SAVE_FAILURE);
        pulse();
        Instant opened = at(TODAY, 13, 0, 0);
        runOnFx(() -> progress.resetForProject(opened));
        assertThat(cell()).as("a project replacement clears both, and shows the opened project's last save")
                .returns(Severity.NORMAL, Cell::severity)
                .returns("✓ Saved 13:00:00", Cell::text);
        assertThat(cell().tooltip()).doesNotContain("failed");
    }

    @Test
    void withBothFailuresStandingTheCellShowsTheHeadsTextRedAndTheTooltipNamesBothCausesAndTheLastGood()
            throws Exception {
        realizeTheStrip();
        Instant saved = at(TODAY, 14, 33, 0);
        Instant seeded = at(TODAY, 14, 33, 30);
        Instant checkpointFailed = at(TODAY, 14, 34, 0);
        Instant headFailed = at(TODAY, 14, 35, 0);
        long generation = progress.projectGeneration();
        progress.recordSaveSucceeded(generation, saved, SaveScope.FULL);
        progress.recordCheckpointsSeeded(seeded, CHECKPOINT, () -> true);
        pulse();

        progress.recordCheckpointFailed(generation, checkpointFailed, CHECKPOINT_FAILURE);
        progress.recordSaveFailed(generation, headFailed, SAVE_FAILURE);
        pulse();

        Cell cell = cell();
        assertThat(cell.text()).isEqualTo("⚠ Save failed · last saved 14:33");
        assertThat(cell.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(cell.tooltip()).isEqualTo(String.join("\n",
                "Save failed at 14:35:00: There is not enough space on the disk",
                "Checkpoint failed at 14:34:00: checkpoints is read-only",
                "Last saved 14:33:00 (full)",
                "Last checkpoint 14:33:30 · checkpoint-047-20261022T181000.daw"));
        assertThat(cell.accessibleText()).isEqualTo("Error: Save failed, last saved 14:33");
        assertThat(cell.accessibleHelp()).as("the accessible help is the tooltip").isEqualTo(cell.tooltip());
    }

    @Test
    void theAccessibleTextSaysTheStateInWordsWithTheSeverityFirst() throws Exception {
        realizeTheStrip();
        long generation = progress.projectGeneration();
        assertThat(cell().accessibleText()).isEqualTo("Not saved yet");

        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        pulse();
        assertThat(cell().accessibleText()).isEqualTo("Saved 14:35:07");

        progress.recordCheckpointsSeeded(LAST_GOOD, CHECKPOINT, () -> true);
        pulse();
        progress.recordCheckpointFailed(generation, SAVED, CHECKPOINT_FAILURE);
        pulse();
        assertThat(cell().accessibleText()).as("ruling L2's example")
                .isEqualTo("Warning: Checkpoint failed, last good 14:32, saved 14:35:07");

        NamedOperation saving = new NamedOperation(ProjectOperationProgress.HEAD_SAVE_OPERATION_NAME);
        progress.addOperation(saving);
        pulse();
        assertThat(cell().accessibleText()).isEqualTo("Warning: Saving project…");
        assertThat(cell().accessibleHelp()).startsWith("Saving project…\nCheckpoint failed at ");
    }

    @Test
    void theSeverityIsTheCellsOwnStyleClass() throws Exception {
        realizeTheStrip();
        progress.recordSaveFailed(progress.projectGeneration(), SAVED, SAVE_FAILURE);
        pulse();

        assertThat(onFx(() -> strip.savedCell().getStyleClass()))
                .contains(StatusStripCell.SEVERITY_CRITICAL_CLASS)
                .doesNotContain(StatusStripCell.SEVERITY_WARN_CLASS);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** {@code time} of {@code day} in the JVM's default zone. */
    private static Instant at(LocalDate day, int hour, int minute, int second) {
        return day.atTime(hour, minute, second).atZone(ZONE).toInstant();
    }

    /** A model whose today starts from a clock pinned at {@code time} of {@code day}. */
    private ProjectOperationProgress modelOn(LocalDate day, LocalTime time) {
        return new ProjectOperationProgress(dispatcher, Clock.fixed(day.atTime(time).atZone(ZONE).toInstant(), ZONE));
    }

    private void realizeTheStrip() throws Exception {
        runOnFx(() -> {
            strip = new SessionStatusStrip(progress, MotionManager.getDefault());
            new Scene(new Group(strip), 1400, 80);
            strip.applyCss();
            strip.layout();
        });
    }

    /** One pulse of the model's dispatcher, after every FX post made before it. */
    private void pulse() throws Exception {
        runOnFx(dispatcher::pulse);
    }

    private Cell cell() throws Exception {
        return onFx(() -> {
            StatusStripCell saved = strip.savedCell();
            return new Cell(saved.getText(), saved.getSeverity(), saved.getTooltip().getText(),
                    saved.getAccessibleText(), saved.getAccessibleHelp());
        });
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
