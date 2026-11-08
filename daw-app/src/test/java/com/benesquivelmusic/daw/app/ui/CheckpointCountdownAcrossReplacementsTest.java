package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.status.CheckpointCountdownCanvas;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent.RearmReason;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrder;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;

import javafx.beans.value.ChangeListener;
import javafx.scene.Group;
import javafx.scene.Scene;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, the checkpoint countdown across project replacements (rulings
 * G13 #2, M4, N2): only the checkpoints' re-arms write it, and only the end
 * of the checkpoint generation clears it. A snapshot restore keeps the
 * project's checkpoints running, so it keeps the countdown — never passing
 * through "No checkpoint scheduled" — and a re-arm decided just before the
 * restore, its keyed write still pending in the same frame, survives it. An
 * open or a new project shows no countdown while its checkpoints are being
 * seeded, then the due time their re-arm reported. An open that fails has
 * abandoned the project on screen and ended its checkpoints, so it shows no
 * countdown either, and leaves the Saved cell as it was.
 *
 * <p>Song A's checkpoints have reported their start to the strip before any
 * test begins, so no real re-arm of Song A trails the ones a test hands to
 * the strip's checkpoint-status listener, as the checkpoint manager reports
 * them, for the running generation. Each project folder has a checkpoint
 * writer of its own (story 333), so nothing of Song A holds another
 * project's seeding: the open holds Song B's own seeding, inside its
 * compaction of an ordering fact that names a checkpoint no longer in Song
 * B's folder, held at its publish; a new project's folder holds nothing to
 * hold, so its test records every value the countdown shows from the switch
 * on.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CheckpointCountdownAcrossReplacementsTest {

    private static final Duration INTERVAL = Duration.ofMinutes(5);
    private static final Instant DUE = Instant.parse("2026-10-23T14:31:00Z");
    private static final Instant LATER_DUE = Instant.parse("2026-10-23T14:36:00Z");

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;

    @BeforeEach
    void openSongA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        awaitTheStripShowsSongAsLastRearm(RearmReason.STARTED);
    }

    @AfterEach
    void close() throws Exception {
        try {
            fixture.close();
        } finally {
            // A failed open took its folder's lock and kept it (the project
            // manager releases a lock only for a project it opened); let it
            // go, and its heartbeat with it, before the folder is deleted.
            fixture.projectManager.getLockManager().release();
        }
    }

    @Test
    void aSnapshotRestoreKeepsTheCountdownOfTheLastRearmAndNeverClearsIt() throws Exception {
        rearmSongAsCheckpoints(DUE);
        fixture.pulse();
        assertThat(onFx(fixture.progress::getNextCheckpointInstant)).as("fixture: the countdown runs").isEqualTo(DUE);
        SnapshotEntry entry = aSnapshotOfTheOpenProject();
        List<Instant> shown = new ArrayList<>();
        ChangeListener<Instant> showing = (_, _, now) -> shown.add(now);
        runOnFx(() -> fixture.progress.nextCheckpointInstantProperty().addListener(showing));

        runOnFx(() -> fixture.snapshots().restoreEntry(entry));
        fixture.pulse();
        runOnFx(() -> fixture.progress.nextCheckpointInstantProperty().removeListener(showing));

        assertThat(onFx(fixture.progress::getNextCheckpointInstant)).as("the armed tick's due time")
                .isEqualTo(DUE);
        assertThat(onFx(fixture.progress::getCheckpointInterval)).isEqualTo(INTERVAL);
        assertThat(onFx(() -> List.copyOf(shown))).as("never \"No checkpoint scheduled\" across the restore")
                .doesNotContainNull();
    }

    @Test
    void aRearmDecidedJustBeforeARestoreInTheSameFrameSurvivesIt() throws Exception {
        rearmSongAsCheckpoints(DUE);
        fixture.pulse();
        SnapshotEntry entry = aSnapshotOfTheOpenProject();

        // A tick's re-arm reaches the FX thread: its keyed write waits for the frame...
        rearmSongAsCheckpoints(LATER_DUE);
        runOnFx(() -> { });
        assertThat(onFx(fixture.progress::getNextCheckpointInstant)).as("fixture: not applied yet").isEqualTo(DUE);
        // ...and a restore begins a new project generation before that frame.
        runOnFx(() -> fixture.snapshots().restoreEntry(entry));
        fixture.pulse();

        assertThat(onFx(fixture.progress::getNextCheckpointInstant)).as("the later re-arm survived the restore")
                .isEqualTo(LATER_DUE);
    }

    @Test
    void anOpenShowsNoCountdownWhileItsCheckpointsAreSeededThenTheDueTimeTheirStartReported() throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        rearmSongAsCheckpoints(DUE);
        fixture.pulse();
        SaveFlowFixture.Publishes.Hold seedingHeldBehind = holdTheSeedingOf(songB);

        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(songB))).isTrue();
        seedingHeldBehind.awaitHeld();
        fixture.pulse();

        assertThat(onFx(fixture.progress::getNextCheckpointInstant))
                .as("\"No checkpoint scheduled\" while Song B's checkpoints are being seeded").isNull();
        long songBGeneration = fixture.checkpoints.runningGeneration().orElseThrow();
        assertThat(rearms()).as("fixture: Song B's seeding is held")
                .noneMatch(r -> r.generation() == songBGeneration);

        seedingHeldBehind.release();
        Instant started = theDueTimeOfTheLastRearmOf(songBGeneration, RearmReason.STARTED);
        fixture.awaitOnFx(() -> started.equals(fixture.progress.getNextCheckpointInstant()),
                "the countdown of Song B's first tick");
    }

    @Test
    void aNewProjectShowsNoCountdownWhileItsCheckpointsAreSeededThenTheDueTimeTheyReported() throws Exception {
        Path parent = Files.createDirectories(workspace.resolve("new"));
        rearmSongAsCheckpoints(DUE);
        fixture.pulse();
        assertThat(onFx(fixture.progress::getNextCheckpointInstant)).as("fixture: the countdown runs").isEqualTo(DUE);
        List<Instant> shown = new ArrayList<>();
        ChangeListener<Instant> showing = (_, _, now) -> shown.add(now);
        runOnFx(() -> fixture.progress.nextCheckpointInstantProperty().addListener(showing));

        try {
            runOnFx(() -> {
                fixture.lifecycle().setNewProjectLocationPrompt(() -> parent);
                fixture.lifecycle().onNewProject();
            });
            // Story 333 — New creates its project's folder, and starts its checkpoints, off the FX thread.
            fixture.awaitOnFx(() -> fixture.checkpoints.runningGeneration().isPresent(),
                    "the new project's checkpoints started");
            long newGeneration = fixture.checkpoints.runningGeneration().orElseThrow();
            theDueTimeOfTheLastRearmOf(newGeneration, RearmReason.STARTED);
            fixture.awaitOnFx(() -> {
                Instant now = fixture.progress.getNextCheckpointInstant();
                return now != null && now.equals(lastRearmOf(newGeneration).nextDue());
            }, "the countdown of the new project's last re-arm");

            List<Instant> values = onFx(() -> new ArrayList<>(shown));
            assertThat(values).as("\"No checkpoint scheduled\" first, while the new project's checkpoints were seeded")
                    .isNotEmpty().first().isNull();
            assertThat(values).as("never Song A's due time again").doesNotContain(DUE, LATER_DUE);
        } finally {
            runOnFx(() -> fixture.progress.nextCheckpointInstantProperty().removeListener(showing));
        }
    }

    /**
     * Ruling N2: Song A's tick is due, and the strip's listener has applied a
     * later re-arm of Song A, its keyed write still pending, when a File ▸
     * Open whose project file does not parse runs before that frame. The
     * failed open abandoned Song A and ended its checkpoints with no
     * replacement, which clears the countdown in the open's own FX step, and
     * the pending re-arm sets no due time, so from the next frame the
     * countdown cell reads "No checkpoint scheduled" — never a due time that
     * has stopped — and the Saved cell still shows Song A's save.
     */
    @Test
    void aFailedOpenEndsTheCountdownOfTheProjectItAbandonedAndLeavesTheSavedCell() throws Exception {
        Group root = onFx(Group::new);
        SessionStatusStrip strip = onFx(() -> aStripOverTheModelIn(root));
        try {
            runOnFx(() -> fixture.project.get().markDirty());
            fixture.save();
            fixture.settle();
            awaitTheStripShowsSongAsLastRearm(RearmReason.WRITTEN);
            rearmSongAsCheckpoints(DUE);
            fixture.pulse();
            assertThat(onFx(fixture.progress::getNextCheckpointInstant)).as("fixture: the countdown runs")
                    .isEqualTo(DUE);
            String saved = onFx(() -> strip.savedCell().getText());
            assertThat(saved).as("fixture: Song A's save is on the Saved cell").startsWith("✓ Saved ");
            Path malformed = Files.createDirectories(workspace.resolve("Malformed"));
            Files.writeString(malformed.resolve("project.daw"), "<daw-project><tracks>");

            // A tick's re-arm reaches the FX thread: its keyed write waits for the frame...
            rearmSongAsCheckpoints(LATER_DUE);
            runOnFx(() -> { });
            assertThat(onFx(fixture.progress::getNextCheckpointInstant)).as("fixture: not applied yet")
                    .isEqualTo(DUE);
            // ...and a File ▸ Open that fails ends Song A's checkpoints before that frame.
            runOnFx(() -> {
                fixture.lifecycle().setOpenProjectPrompt(() -> malformed);
                fixture.lifecycle().onOpenProject();
            });
            assertThat(onFx(fixture.progress::getNextCheckpointInstant)).as("the abandon ended it in the open's step")
                    .isNull();
            fixture.pulse();

            assertThat(fixture.notifications().getLast()).satisfies(entry -> {
                assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                assertThat(entry.message()).startsWith("Open failed: ");
            });
            assertThat(onFx(fixture.progress::getNextCheckpointInstant)).as("Song A's checkpoints ended with it")
                    .isNull();
            assertThat(onFx(() -> countdownCaption(strip))).isEqualTo("No checkpoint scheduled");
            assertThat(onFx(() -> strip.savedCell().getText())).as("the Saved cell is left as it was")
                    .isEqualTo(saved);
        } finally {
            runOnFx(() -> root.getChildren().clear());
        }
    }

    /**
     * Waits until Song A's checkpoints re-armed for {@code reason} and the
     * strip shows that re-arm's due time, so no real re-arm of Song A trails
     * one a test hands over.
     */
    private void awaitTheStripShowsSongAsLastRearm(RearmReason reason) throws Exception {
        long songA = fixture.checkpoints.runningGeneration().orElseThrow();
        Instant due = theDueTimeOfTheLastRearmOf(songA, reason);
        fixture.awaitOnFx(() -> due.equals(fixture.progress.getNextCheckpointInstant()),
                "the strip shows Song A's " + reason + " re-arm");
    }

    /** A re-arm of Song A's running checkpoints, as their scheduler reports it to the strip's listener. */
    private void rearmSongAsCheckpoints(Instant due) {
        fixture.status().accept(new CheckpointEvent.Rearmed(fixture.checkpoints.runningGeneration().orElseThrow(), due,
                INTERVAL, RearmReason.WRITTEN));
    }

    /** An in-memory snapshot of the open project, as the browser lists one. */
    private SnapshotEntry aSnapshotOfTheOpenProject() throws Exception {
        String document = new ProjectSerializer().serialize(onFx(fixture.project::get));
        return fixture.snapshots().service().createUserCheckpoint("Before the restore", document);
    }

    /**
     * Holds the next seeding of {@code folder}'s checkpoints inside its
     * claim: the folder's ordering fact gains the name of a checkpoint that is
     * not there, which the seeding compacts away through the checkpoint
     * writer — held at that publish, before it reports.
     */
    private SaveFlowFixture.Publishes.Hold holdTheSeedingOf(Path folder) throws Exception {
        Path checkpoints = Files.createDirectories(folder.resolve("checkpoints"));
        Set<String> covered = new HashSet<>(CheckpointOrder.read(checkpoints));
        covered.add("checkpoint-999-20260101T000000.daw");
        CheckpointOrder.record(checkpoints, covered);
        Path fact = checkpoints.resolve(CheckpointOrder.FILE_NAME);
        return fixture.publishes.holdNext(fact::equals);
    }

    /** The strip over the fixture's model, realized headlessly in a {@code Scene} under {@code root}. FX thread. */
    private SessionStatusStrip aStripOverTheModelIn(Group root) {
        SessionStatusStrip strip = new SessionStatusStrip(fixture.progress, MotionManager.getDefault());
        root.getChildren().add(strip);
        new Scene(root, 1400, 80);
        strip.applyCss();
        strip.layout();
        return strip;
    }

    /** What the strip's countdown cell reads now. FX thread. */
    private static String countdownCaption(SessionStatusStrip strip) {
        Instant next = strip.checkpointCountdown().getNextCheckpointInstant();
        return CheckpointCountdownCanvas.countdownText(next == null ? null : Duration.between(Instant.now(), next));
    }

    /** Waits until {@code generation}'s checkpoints re-armed for {@code reason}; returns that re-arm's due time. */
    private Instant theDueTimeOfTheLastRearmOf(long generation, RearmReason reason) throws Exception {
        fixture.awaitOnFx(() -> rearms().stream().anyMatch(r -> r.generation() == generation && r.reason() == reason),
                "the " + reason + " re-arm of checkpoints #" + generation);
        return rearms().stream().filter(r -> r.generation() == generation && r.reason() == reason)
                .reduce((first, second) -> second).orElseThrow().nextDue();
    }

    private CheckpointEvent.Rearmed lastRearmOf(long generation) {
        return rearms().stream().filter(r -> r.generation() == generation).reduce((first, second) -> second)
                .orElseThrow();
    }

    /** The re-arms the checkpoints reported so far, oldest first. */
    private List<CheckpointEvent.Rearmed> rearms() {
        List<CheckpointEvent.Rearmed> rearms = new ArrayList<>();
        for (CheckpointEvent event : fixture.checkpointEvents) {
            if (event instanceof CheckpointEvent.Rearmed rearmed) {
                rearms.add(rearmed);
            }
        }
        return rearms;
    }
}
