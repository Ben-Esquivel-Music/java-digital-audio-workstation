package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent.RearmReason;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331 with autosave disabled (rulings H3, J2): the project's
 * checkpoints still run — a user's Create Checkpoint is written — but no
 * tick is armed, so the strip shows no countdown, even for a re-arm whose
 * delivery trails the reconfiguration that disabled autosave; and Ctrl+S
 * still writes {@code project.daw} and shows the head's success, with no
 * checkpoint after it.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class AutosaveDisabledStripTest {

    private static final AutoSaveConfig ENABLED = AutoSaveConfig.DEFAULT;
    private static final AutoSaveConfig DISABLED = ENABLED.withEnabled(false);

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;

    @AfterEach
    void close() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    /**
     * The race ruling H3 names: a re-arm handed to the listener while
     * autosave is still enabled, on the thread that reports it, and applied
     * after a reconfiguration disabled autosave. The countdown's
     * {@code isRunning()} is asked on the FX thread when the frame applies
     * the re-arm, so it shows nothing; asked where the re-arm was handed over,
     * it would have answered {@code true}.
     */
    @Test
    void noCountdownIsShownForARearmHandedOverWhileEnabledAndAppliedAfterTheReconfiguration() throws Exception {
        fixture = new SaveFlowFixture(workspace, ENABLED);
        // A re-arm a full interval from now, reported to the listener the fixture wired.
        fixture.checkpoints.reconfigure(ENABLED);
        fixture.awaitOnFx(() -> fixture.progress.getNextCheckpointInstant() != null,
                "fixture: enabled, the countdown runs");
        Instant due = Instant.now().plus(Duration.ofMinutes(7));
        long running = fixture.checkpoints.runningGeneration().orElseThrow();

        // Handed over on this thread — not the FX thread — while autosave is still enabled...
        assertThat(fixture.checkpoints.isRunning()).as("fixture: still enabled at the hand-over").isTrue();
        fixture.status().accept(new CheckpointEvent.Rearmed(running, due.plusSeconds(1),
                ENABLED.autoSaveInterval(), RearmReason.TICK_DROPPED));
        // ...then autosave is disabled, and only then does the frame apply it.
        fixture.checkpoints.reconfigure(DISABLED);
        fixture.pulse();

        assertThat(onFx(fixture.progress::getNextCheckpointInstant)).as("no countdown while autosave is off")
                .isNull();
        assertThat(fixture.checkpoints.isRunning()).isFalse();
    }

    @Test
    void ctrlSStillSavesAndShowsTheHeadStateWithNoCheckpointAfterIt() throws Exception {
        fixture = new SaveFlowFixture(workspace, DISABLED);
        runOnFx(() -> fixture.project.get().markDirty());

        fixture.save();
        fixture.settle();

        assertThat(fixture.publishes.done(SaveFlowFixture::isHead)).hasSize(1);
        assertThat(fixture.publishes.done(SaveFlowFixture::isCheckpoint)).as("no checkpoint after the save")
                .isEmpty();
        assertThat(onFx(fixture.progress::isLastSaveSucceeded)).isTrue();
        assertThat(onFx(fixture.progress::getSavesSucceeded)).isEqualTo(1L);
        assertThat(onFx(() -> fixture.project.get().isDirty())).isFalse();
        assertThat(fixture.notifications()).isEmpty();
        assertThat(onFx(fixture.progress::getNextCheckpointInstant)).isNull();
    }

    @Test
    void createCheckpointStillWrites() throws Exception {
        fixture = new SaveFlowFixture(workspace, DISABLED);

        runOnFx(() -> fixture.snapshots().createCheckpointWithLabel("Before the edit"));
        fixture.awaitOnFx(() -> fixture.progress.getLastCheckpointFile() != null, "the user checkpoint");

        assertThat(SaveFlowFixture.checkpointFiles(fixture.songA)).singleElement()
                .satisfies(file -> assertThat(Files.readString(file)).contains("Song A"));
        assertThat(onFx(fixture.progress::isLastCheckpointSucceeded)).isTrue();
        assertThat(onFx(fixture.progress::getNextCheckpointInstant)).as("and arms no countdown").isNull();
        assertThat(fixture.notifications()).isEmpty();
    }
}
