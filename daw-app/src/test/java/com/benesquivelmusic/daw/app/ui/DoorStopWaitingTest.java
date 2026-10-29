package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.PendingSwitch;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, ruling R8 (Goals — Tests, "Hung-save", the door half): a door
 * waiting for a save stays live, and after 10 s of waiting shows one WARNING
 * — "Still saving “Song A” after 10 seconds — the disk may not be responding.
 * Open Project continues when the save finishes; stop waiting to keep the
 * open project as it is." — with a {@code Stop waiting} action, once per
 * wait and never rate-limited. Stop waiting cancels the door: the INFO
 * "Open Project was cancelled; “Song A” stays open as it is, and its save
 * goes on.", and when the save finally lands the door does nothing — the
 * project stays as it is. The wait has an epoch: a timer or an action of a
 * wait that ended does nothing. The door that waits for its own prompt's
 * save is a wait too.
 *
 * <p>The save is held at its head publish; the 10 s threshold is the
 * fixture's manual clock, fired when the test says. Kills: a Stop waiting
 * that only clears the waiting door (the held save's continuation then
 * opens Song B); a wait with no epoch (an old action cancels a new wait); a
 * timer the wait's end does not cancel; a prompt's save whose follow-up
 * ignores a stopped wait.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DoorStopWaitingTest {

    private static final Duration TEN_SECONDS = ProjectLifecycleController.DOOR_WAIT_WARNING_DELAY;
    private static final String STILL_SAVING = "Still saving “Song A” after 10 seconds — the disk may not be"
            + " responding. Open Project continues when the save finishes; stop waiting to keep the open project as"
            + " it is.";
    private static final String CANCELLED = "Open Project was cancelled; “Song A” stays open as it is, and its"
            + " save goes on.";

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;
    private Path songB;
    private final AtomicInteger choosersShown = new AtomicInteger();

    @BeforeEach
    void openSongA() throws Exception {
        assertThat(TEN_SECONDS).as("ruling R8's threshold").isEqualTo(Duration.ofSeconds(10));
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        songB = fixture.anotherProjectOnDisk("Song B");
        runOnFx(() -> fixture.lifecycle().setOpenProjectPrompt(() -> {
            choosersShown.incrementAndGet();
            return songB;
        }));
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void stopWaitingCancelsTheDoorAndWhenTheSaveLandsNothingIsOpened() throws Exception {
        SaveFlowFixture.Publishes.Hold head = aSaveHeldAtItsHead();
        runOnFx(() -> fixture.lifecycle().onOpenProject());
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .isEqualTo(new PendingSwitch.WaitingDoor("Open Project"));
        assertThat(warnings()).as("nothing before 10 s").isEmpty();

        assertThat(onFx(() -> fixture.doorWaitTimers.fire(TEN_SECONDS))).isEqualTo(1);
        assertThat(onFx(() -> fixture.doorWaitTimers.fire(TEN_SECONDS))).as("once per wait").isZero();
        NotificationEntry warning = warnings().getFirst();
        assertThat(warnings()).hasSize(1);
        assertThat(warning.message()).isEqualTo(STILL_SAVING);
        assertThat(warning.actionLabel()).contains("Stop waiting");

        runOnFx(() -> warning.action().orElseThrow().run());
        assertThat(fixture.notifications()).filteredOn(entry -> entry.level() == NotificationLevel.INFO)
                .extracting(NotificationEntry::message).containsExactly(CANCELLED);
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending())).as("the door is no longer pending")
                .isEqualTo(new PendingSwitch.None());

        head.release();
        fixture.settle();
        fixture.settle();
        assertThat(choosersShown).as("the stopped door never resumed").hasValue(0);
        assertThat(fixture.currentProjectIs(fixture.songA)).as("Song A stays open as it is").isTrue();
        assertThat(onFx(() -> fixture.project.get().isDirty())).as("its save went on, and landed").isFalse();
        assertThat(fixture.notifications()).extracting(NotificationEntry::level).doesNotContain(NotificationLevel.ERROR);
        runOnFx(() -> warning.action().orElseThrow().run());
        assertThat(fixture.notifications()).filteredOn(entry -> entry.level() == NotificationLevel.INFO)
                .as("the action of a wait that ended does nothing").hasSize(1);
    }

    @Test
    void aWaitThatEndsBeforeTenSecondsShowsNothingAndItsTimerIsCancelled() throws Exception {
        SaveFlowFixture.Publishes.Hold head = aSaveHeldAtItsHead();
        runOnFx(() -> fixture.lifecycle().onOpenProject());
        head.release();
        fixture.awaitOnFx(() -> fixture.currentProjectIs(songB), "Open opens Song B once the save landed");

        assertThat(fixture.doorWaitTimers.pending(TEN_SECONDS)).as("the ended wait's timer was cancelled").isEmpty();
        assertThat(onFx(() -> fixture.doorWaitTimers.fire(TEN_SECONDS))).isZero();
        assertThat(warnings()).isEmpty();
        assertThat(choosersShown).hasValue(1);
    }

    @Test
    void anActionOfAnEndedWaitNeverCancelsTheNextWait() throws Exception {
        SaveFlowFixture.Publishes.Hold first = aSaveHeldAtItsHead();
        runOnFx(() -> fixture.lifecycle().onOpenProject());
        runOnFx(() -> fixture.doorWaitTimers.fire(TEN_SECONDS));
        NotificationEntry firstWarning = warnings().getFirst();
        runOnFx(() -> firstWarning.action().orElseThrow().run());
        first.release();
        fixture.settle();

        SaveFlowFixture.Publishes.Hold second = aSaveHeldAtItsHead();
        runOnFx(() -> fixture.lifecycle().onOpenProject());
        runOnFx(() -> firstWarning.action().orElseThrow().run());
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending())).as("the second wait goes on")
                .isEqualTo(new PendingSwitch.WaitingDoor("Open Project"));
        assertThat(fixture.notifications()).filteredOn(entry -> entry.level() == NotificationLevel.INFO)
                .as("only the first wait was cancelled").hasSize(1);

        assertThat(onFx(() -> fixture.doorWaitTimers.fire(TEN_SECONDS))).as("stage-4 review N12: the second wait"
                + " warns as the first did — never rate-limited").isEqualTo(1);
        assertThat(warnings()).extracting(NotificationEntry::message).containsExactly(STILL_SAVING, STILL_SAVING);

        second.release();
        fixture.awaitOnFx(() -> fixture.currentProjectIs(songB), "the second wait's door opens Song B");
        assertThat(choosersShown).hasValue(1);
    }

    /**
     * The studio engineer's stage-4 N3: a wait that ends while its WARNING
     * still shows on the bar takes it off, so "Still saving … stop waiting"
     * never stays up for a door that already went on.
     */
    @Test
    void aWaitThatEndsTakesItsWarningOffTheBar() throws Exception {
        // The door goes on to its chooser, which is cancelled: nothing else shows a toast over the WARNING.
        runOnFx(() -> fixture.lifecycle().setOpenProjectPrompt(() -> {
            choosersShown.incrementAndGet();
            return null;
        }));
        SaveFlowFixture.Publishes.Hold head = aSaveHeldAtItsHead();
        runOnFx(() -> fixture.lifecycle().onOpenProject());
        runOnFx(() -> fixture.doorWaitTimers.fire(TEN_SECONDS));
        assertThat(onFx(() -> fixture.notificationBar().getMessage())).as("fixture: the WARNING shows")
                .isEqualTo(STILL_SAVING);

        head.release();
        fixture.awaitOnFx(() -> choosersShown.get() == 1, "the save landed and the door went on to its chooser");

        assertThat(fixture.notifications()).as("fixture: no later toast covered the WARNING").hasSize(1);
        assertThat(onFx(() -> fixture.notificationBar().getCurrentLevel() == NotificationLevel.WARNING
                && STILL_SAVING.equals(fixture.notificationBar().getMessage())))
                .as("the ended wait's WARNING is off the bar").isFalse();
    }

    /**
     * The studio engineer's stage-4 S3: a restored archive's late switch that
     * waits for a save, stopped, says the archive was restored and offers to
     * open it later (ruling K3's INFO with its Open action) — never that
     * Restore from Archive "was cancelled".
     */
    @Test
    void stoppingARestoredArchivesWaitOffersToOpenItLaterAndCancelsNothing() throws Exception {
        Path archive = fixture.archiveOnDisk("Song R");
        Path restored = java.nio.file.Files.createDirectories(workspace.resolve("restored"));
        SaveFlowFixture.Publishes.Hold extraction = fixture.publishes.holdNext(
                target -> SaveFlowFixture.isHead(target) && target.startsWith(restored));
        runOnFx(() -> {
            fixture.lifecycle().setFileWriter(fixture.writer);
            fixture.lifecycle().setArchiveToRestorePrompt(() -> archive);
            fixture.lifecycle().setRestoreParentPrompt(() -> restored);
            fixture.lifecycle().onRestoreFromArchive();
        });
        extraction.awaitHeld();
        runOnFx(() -> fixture.project.get().markDirty());
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(
                target -> SaveFlowFixture.isHead(target) && target.startsWith(fixture.songA));
        fixture.save();
        head.awaitHeld();
        extraction.release();
        fixture.awaitOnFx(() -> new PendingSwitch.WaitingDoor(ProjectLifecycleController.RESTORE_FROM_ARCHIVE_DOOR)
                .equals(fixture.lifecycle().closeReadiness().pending()), "the late load waits for Song A's save");
        Path destination;
        try (java.util.stream.Stream<Path> folders = java.nio.file.Files.list(restored)) {
            destination = folders.filter(java.nio.file.Files::isDirectory).findFirst().orElseThrow();
        }

        runOnFx(() -> fixture.doorWaitTimers.fire(TEN_SECONDS));
        NotificationEntry warning = warnings().getLast();
        runOnFx(() -> warning.action().orElseThrow().run());

        List<NotificationEntry> infos = fixture.notifications().stream()
                .filter(entry -> entry.level() == NotificationLevel.INFO).toList();
        assertThat(infos).extracting(NotificationEntry::message).as("K3's INFO, never door.cancelled")
                .containsExactly(ProjectLifecycleController.restoredArchiveNotOpenedMessage(destination));
        assertThat(infos.getFirst().actionLabel()).contains(ProjectLifecycleController.OPEN_RESTORED_ARCHIVE_ACTION);
        assertThat(destination).as("the archive stays restored").isDirectory();
        head.release();
        fixture.settle();
        fixture.settle();
        assertThat(fixture.currentProjectIs(fixture.songA)).as("Song A stays open").isTrue();
    }

    /**
     * The studio engineer's stage-4 S3: Recover Session's late switch whose
     * changes were already written, stopped, says so
     * ({@code door.cancelled.alreadyDone}).
     */
    @Test
    void stoppingARecoveredProjectsWaitSaysTheChangesWereWrittenAndThatItWasNotOpened() throws Exception {
        SaveFlowFixture.Publishes.Hold head = aSaveHeldAtItsHead();
        Path projectFile = songB.resolve("project.daw");
        runOnFx(() -> fixture.lifecycle().openRecoveredProject(songB, projectFile, 3, List.of()));
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .isEqualTo(new PendingSwitch.WaitingDoor(ProjectLifecycleController.RECOVER_SESSION_DOOR));

        runOnFx(() -> fixture.doorWaitTimers.fire(TEN_SECONDS));
        NotificationEntry warning = warnings().getFirst();
        runOnFx(() -> warning.action().orElseThrow().run());

        assertThat(fixture.notifications()).filteredOn(entry -> entry.level() == NotificationLevel.INFO)
                .extracting(NotificationEntry::message).containsExactly("3 recovered changes were written to "
                        + projectFile + "; the project was not opened — “Song A” stays open as it is, and its save goes"
                        + " on. Open it later with File ▸ Open Project…");
        head.release();
        fixture.settle();
        fixture.settle();
        assertThat(fixture.currentProjectIs(fixture.songA)).isTrue();
    }

    @Test
    void theDoorWaitingForItsOwnPromptsSaveCanStopWaitingAndItsSavedOutcomeOpensNothing() throws Exception {
        runOnFx(() -> fixture.project.get().markDirty());
        fixture.promptAnswer = "Save";
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        runOnFx(() -> fixture.lifecycle().onOpenProject());
        head.awaitHeld();
        assertThat(fixture.prompts).as("fixture: the door asked, and Save was pressed").hasValue(1);
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .isEqualTo(new PendingSwitch.WaitingDoor("Open Project"));

        runOnFx(() -> fixture.doorWaitTimers.fire(TEN_SECONDS));
        NotificationEntry warning = warnings().getFirst();
        assertThat(warning.message()).isEqualTo(STILL_SAVING);
        runOnFx(() -> warning.action().orElseThrow().run());
        head.release();
        fixture.settle();
        fixture.settle();

        assertThat(onFx(() -> fixture.project.get().isDirty())).as("the prompt's save landed").isFalse();
        assertThat(choosersShown).as("and the stopped door did not go on").hasValue(0);
        assertThat(fixture.currentProjectIs(fixture.songA)).isTrue();
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending())).isEqualTo(new PendingSwitch.None());
    }

    /** Song A edited, and its Ctrl+S held at its head publish, so a door waits for it. */
    private SaveFlowFixture.Publishes.Hold aSaveHeldAtItsHead() throws Exception {
        runOnFx(() -> fixture.project.get().markDirty());
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        head.awaitHeld();
        return head;
    }

    private List<NotificationEntry> warnings() throws Exception {
        return fixture.notifications().stream().filter(entry -> entry.level() == NotificationLevel.WARNING).toList();
    }
}
