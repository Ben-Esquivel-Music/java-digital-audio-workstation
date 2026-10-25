package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell.Severity;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;

import javafx.scene.Group;
import javafx.scene.Scene;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, the Saved cell after a snapshot restore (rulings G4, G13 #4):
 * a restore keeps the project manager's project and its {@code project.daw},
 * so the cell then shows when that file was last durably written — the last
 * save that ended {@code Saved} — never the capture stamp of a save that
 * failed, which the project manager's in-memory metadata keeps. Shown after a
 * successful save and a failed one, for a restore over a clean project and
 * one answered Discard; the cell and its tooltip agree.
 *
 * <p>The strip is realized headlessly in a {@code Scene} over the fixture's
 * model, whose keyed facts apply only in the fixture's pulse; the model's
 * today is set to the successful save's date, so no expectation depends on
 * when the test runs. The failed save is taken in a later second than the
 * successful one, so the two times read apart on the cell.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SnapshotRestoreShowsTheLastSaveThatLandedTest {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;
    private SessionStatusStrip strip;

    @BeforeEach
    void openSongA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        runOnFx(() -> {
            strip = new SessionStatusStrip(fixture.progress, MotionManager.getDefault());
            new Scene(new Group(strip), 1400, 80);
            strip.applyCss();
            strip.layout();
        });
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void aRestoreOverACleanProjectAfterAFailedSaveShowsTheLastSaveThatLanded() throws Exception {
        Instant landed = aSuccessfulSaveThenAFailedOne();
        SnapshotEntry entry = aSnapshotOfTheOpenProject();
        runOnFx(() -> fixture.project.get().markClean());

        runOnFx(() -> fixture.snapshots().restoreEntry(entry));
        fixture.pulse();

        assertThat(fixture.prompts).as("a clean project is replaced without a prompt").hasValue(0);
        assertTheCellShowsTheSaveThatLanded(landed);
    }

    @Test
    void aRestoreAnsweredDiscardAfterAFailedSaveShowsTheLastSaveThatLanded() throws Exception {
        Instant landed = aSuccessfulSaveThenAFailedOne();
        SnapshotEntry entry = aSnapshotOfTheOpenProject();
        fixture.promptAnswer = "Discard";

        runOnFx(() -> fixture.snapshots().restoreEntry(entry));
        fixture.pulse();

        assertThat(fixture.prompts).as("the failed save left the project dirty: Discard was asked").hasValue(1);
        assertTheCellShowsTheSaveThatLanded(landed);
    }

    /**
     * The Saved time an open seeds: Song B, opened from disk and never saved
     * since, then restored from a snapshot with no save, still shows when
     * Song B's {@code project.daw} was last written — its
     * {@code ProjectMetadata.lastModified} — not "Not saved yet". The model's
     * today is the day after that save, so the cell carries its date.
     */
    @Test
    void aRestoreOfAProjectOpenedAndNeverSavedSinceShowsTheSaveItWasOpenedWith() throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        Instant lastModified = new ProjectDeserializer()
                .deserialize(Files.readString(songB.resolve("project.daw")), songB).getMetadata().lastModified();
        assertThat(lastModified).as("fixture: Song B was saved").isNotNull();
        fixture.progress.setToday(lastModified.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1));
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(songB))).as("fixture: Song B opens").isTrue();
        fixture.pulse();
        SnapshotEntry entry = aSnapshotOfTheOpenProject();

        runOnFx(() -> fixture.snapshots().restoreEntry(entry));
        fixture.pulse();

        assertThat(fixture.prompts).as("the opened project is clean: no prompt").hasValue(0);
        assertThat(onFx(fixture.progress::getLastSaveTime)).as("when Song B's project.daw was last written")
                .isEqualTo(lastModified);
        String when = DAY.format(lastModified.atZone(ZoneId.systemDefault())) + " "
                + CLOCK.format(lastModified.atZone(ZoneId.systemDefault()));
        Cell shown = cell();
        assertThat(shown.text()).isEqualTo("✓ Saved " + when);
        assertThat(shown.severity()).isEqualTo(Severity.NORMAL);
        assertThat(shown.tooltip()).as("the tooltip agrees").startsWith("Last saved " + when + " (full)");
    }

    /**
     * A successful Ctrl+S, then — in a later second — one whose head write
     * fails; returns when {@code project.daw} was last written.
     */
    private Instant aSuccessfulSaveThenAFailedOne() throws Exception {
        runOnFx(() -> fixture.project.get().markDirty());
        fixture.save();
        fixture.settle();
        Instant landed = onFx(fixture.progress::getLastSaveTime);
        assertThat(landed).as("fixture: the save landed").isNotNull();
        fixture.progress.setToday(landed.atZone(ZoneId.systemDefault()).toLocalDate());
        while (!Instant.now().truncatedTo(ChronoUnit.SECONDS).isAfter(landed.truncatedTo(ChronoUnit.SECONDS))) {
            Thread.sleep(20);
        }

        runOnFx(() -> fixture.project.get().markDirty());
        fixture.publishes.failNext(SaveFlowFixture::isHead, new IOException("injected head write failure"));
        fixture.save();
        fixture.settle();

        assertThat(onFx(fixture.progress::isLastSaveSucceeded)).as("fixture: the second save failed").isFalse();
        Instant stampedAtTheFailedCapture = fixture.projectManager.getCurrentProject().lastModified();
        assertThat(stampedAtTheFailedCapture)
                .as("non-vacuity: the project manager's metadata carries the failed save's capture stamp")
                .isAfter(landed);
        assertThat(cell().text()).as("fixture: the head failure, its last save the one that landed")
                .startsWith("⚠ Save failed · last saved ");
        return landed;
    }

    /** An in-memory snapshot of the open project, as the browser lists one. */
    private SnapshotEntry aSnapshotOfTheOpenProject() throws Exception {
        String document = new ProjectSerializer().serialize(onFx(fixture.project::get));
        return fixture.snapshots().service().createUserCheckpoint("Before the failed save", document);
    }

    private void assertTheCellShowsTheSaveThatLanded(Instant landed) throws Exception {
        String time = CLOCK.format(landed.atZone(ZoneId.systemDefault()));
        assertThat(onFx(fixture.progress::getLastSaveTime)).as("the time project.daw was last written")
                .isEqualTo(landed);
        Cell shown = cell();
        assertThat(shown.text()).isEqualTo("✓ Saved " + time);
        assertThat(shown.severity()).isEqualTo(Severity.NORMAL);
        assertThat(shown.tooltip()).as("the tooltip agrees").startsWith("Last saved " + time + " (full)");
    }

    /** What the Saved cell shows, read on the FX thread. */
    private record Cell(String text, Severity severity, String tooltip) { }

    private Cell cell() throws Exception {
        return onFx(() -> {
            StatusStripCell saved = strip.savedCell();
            return new Cell(saved.getText(), saved.getSeverity(), saved.getTooltip().getText());
        });
    }
}
