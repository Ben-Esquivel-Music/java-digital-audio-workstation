package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, the switch point: a switch that comes after work off the FX
 * thread — the load after an archive's extraction, a migration roll-back's
 * copy over {@code project.daw}, a recovery's load — never replaces the open
 * project, nor writes its head, while a save this application asked for is
 * still being written: it waits for the save writer to go idle, without
 * blocking the FX thread, and is the one switch pending meanwhile. A door
 * asked while it waits is refused with the INFO naming it; a late switch
 * whose turn comes while a door waits — one that already did its work — is
 * refused with the WARNING that says what was done and names the door
 * (ruling L3 (b)), which goes on.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LateSwitchWaitsForAppWritesTest {

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;

    @BeforeEach
    void openSongA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void aSaveRequestedDuringAnArchiveExtractionKeepsSongAOpenUntilItIsWrittenThenTheRestoreLoads()
            throws Exception {
        Path archive = fixture.archiveOnDisk("Song R");
        Path destination = fixture.restoreDestination("Song R");
        runOnFx(() -> fixture.project.get().markDirty());
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);

        runOnFx(() -> {
            fixture.lifecycle().startArchiveRestore(archive, destination);
            // During the extraction — its outcome reaches the FX thread after this step — the user presses Ctrl+S.
            fixture.lifecycle().onSaveProject();
        });
        head.awaitHeld();
        awaitTheExtractionsOutcome();

        assertThat(openProject()).as("Song A stays open while its save is written").isEqualTo(fixture.songA);
        AtomicBoolean doorRan = new AtomicBoolean();
        runOnFx(() -> fixture.lifecycle().whenProjectMayClose(ProjectLifecycleController.OPEN_PROJECT_DOOR,
                () -> doorRan.set(true)));
        assertThat(fixture.notifications()).as("a door meanwhile names the restore that waits").singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.INFO);
                    assertThat(entry.message()).isEqualTo("Still saving — Restore from Archive continues when the"
                            + " save finishes.");
                });

        head.release();
        fixture.awaitOnFx(() -> destination.equals(fixture.projectManager.getCurrentProject().projectPath()),
                "the restore loads once the save is written");
        assertThat(fixture.publishes.done(SaveFlowFixture::isHead)).as("the save was written into Song A first")
                .singleElement().satisfies(publish ->
                        assertThat(publish.target().getParent()).isEqualTo(fixture.songA));
        assertThat(fixture.prompts).as("no edit since the door: no prompt").hasValue(0);
        assertThat(doorRan).as("the refused door never runs").isFalse();
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .anyMatch(message -> message.startsWith("Restored archive: Song R"));
    }

    @Test
    void aRollBackRequestedWhileASaveOfTheSameProjectIsHeldCopiesTheBackupOnlyAfterThatSave() throws Exception {
        DawProject original = new DawProject("Song A", AudioFormat.CD_QUALITY);
        original.createAudioTrack("Before the migration");
        Path backup = Files.writeString(fixture.songA.resolve("project.daw.v1.20260101-000000.bak"),
                new ProjectSerializer().serialize(original));
        int current = MigrationRegistry.CURRENT_VERSION;
        MigrationReport report = new MigrationReport(1, current,
                List.of(new MigrationReport.AppliedMigration(1, current, "test migration")), Instant.EPOCH);
        DawProject songAModel = fixture.project.get();
        runOnFx(() -> songAModel.markDirty());
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        head.awaitHeld();
        byte[] beforeTheRollBack = Files.readAllBytes(fixture.songA.resolve("project.daw"));

        runOnFx(() -> fixture.lifecycle().rollbackMigration(fixture.songA, report, backup));

        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw")))
                .as("nothing is copied over project.daw while its save is held").isEqualTo(beforeTheRollBack);
        assertThat(onFx(fixture.project::get)).as("and Song A is not reloaded yet").isSameAs(songAModel);

        head.release();
        fixture.awaitOnFx(() -> fixture.project.get() != songAModel, "the roll-back reloads once the save ended");
        SaveOutcomes.awaitIdle(fixture.projectManager);
        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw")))
                .as("project.daw ends as the backup: the save did not overwrite the roll-back")
                .isEqualTo(Files.readAllBytes(backup));
        assertThat(fixture.publishes.done(SaveFlowFixture::isHead)).as("the save was written, before the copy")
                .hasSize(1);
        assertThat(onFx(() -> fixture.project.get().getTracks().getFirst().getName()))
                .isEqualTo("Before the migration");
    }

    @Test
    void aRecoveryLoadWaitsForASaveHeldMeanwhileAndADoorAskedThenIsRefusedNamingIt() throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        runOnFx(() -> fixture.project.get().markDirty());
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        head.awaitHeld();

        runOnFx(() -> fixture.lifecycle().openRecoveredProject(songB, songB.resolve("project.daw"), 3, List.of()));
        AtomicBoolean doorRan = new AtomicBoolean();
        runOnFx(() -> fixture.lifecycle().whenProjectMayClose(ProjectLifecycleController.OPEN_PROJECT_DOOR,
                () -> doorRan.set(true)));

        assertThat(openProject()).as("Song A stays open while its save is written").isEqualTo(fixture.songA);
        assertThat(fixture.notifications()).as("the door is refused, naming the recovery that waits")
                .singleElement().satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.INFO);
                    assertThat(entry.message()).isEqualTo("Still saving — Recover Session continues when the save"
                            + " finishes.");
                });

        head.release();
        fixture.awaitOnFx(() -> songB.equals(fixture.projectManager.getCurrentProject().projectPath()),
                "the recovered project loads once the save is written");
        assertThat(fixture.publishes.done(SaveFlowFixture::isHead)).singleElement()
                .satisfies(publish -> assertThat(publish.target().getParent()).isEqualTo(fixture.songA));
        assertThat(doorRan).isFalse();
    }

    @Test
    void aLateSwitchWhoseTurnComesWhileADoorWaitsIsRefusedNamingTheDoorWhichGoesOn() throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        head.awaitHeld();
        CountDownLatch door = onFx(() -> DoorGate.whenItProceeds(fixture.lifecycle()));

        runOnFx(() -> fixture.lifecycle().openRecoveredProject(songB, songB.resolve("project.daw"), 3, List.of()));

        // Ruling L3 (b): the late load already wrote the recovered changes, so its
        // refusal is a WARNING that says so and names the door that waits.
        assertThat(fixture.notifications()).as("the late load is refused, naming the door that waits")
                .singleElement().satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.message()).isEqualTo("3 recovered changes were written to "
                            + songB.resolve("project.daw") + "; the project was not opened, because "
                            + DoorGate.TEST_DOOR + " is replacing the open project — open it later with"
                            + " File ▸ Open Project…");
                });
        head.release();
        assertThat(door.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("the waiting door goes on").isTrue();
        fixture.settle();
        assertThat(openProject()).as("the refused late load never ran").isEqualTo(fixture.songA);
    }

    private Path openProject() throws Exception {
        return onFx(() -> fixture.projectManager.getCurrentProject().projectPath());
    }

    /** Waits, bounded, until the extraction's outcome reached the FX thread: its strip operation is gone. */
    private void awaitTheExtractionsOutcome() throws Exception {
        fixture.awaitOnFx(() -> fixture.progress.getOperations().stream()
                        .noneMatch(operation -> operation.name().equals("Restoring archive…")),
                "the extraction's outcome");
        runOnFx(() -> { });
    }
}
