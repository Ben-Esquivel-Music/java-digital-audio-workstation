package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Map;
import java.util.TreeMap;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.await;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ProjectManager#adoptPublishedCopy} (story 333, ruling R5; research
 * "Session management: Robust project save/load with undo/redo history"):
 * the published copy becomes the open project without a reload — the same
 * live model, so the session and its undo history go on — under the copy's
 * own lock, taken after the publish and before the switch, with its
 * checkpoints started and an entry in Recent Projects; its saves and
 * checkpoints land in the copy, never in the original, whose lock is released
 * by its close. A contract breach (a project still open, another folder's
 * lease, a read-only lease) installs nothing. A lock lost between the old
 * project's close and the adoption is no refusal (the studio engineer's
 * ruling 3 Q1): the copy is installed under the lost lock — the stolen-lock
 * state, end to end — and the result reports the check.
 */
class AdoptPublishedCopyTest {

    @TempDir
    Path tmp;

    private Preferences preferences;
    private RecentProjectsStore recents;
    /** Makes every lock-file read of this manager's leases throw, once set (the Unverified case). */
    private volatile IOException lockReadFailure;
    private ProjectManager manager;
    private DawProject project;
    private Path songA;

    @BeforeEach
    void songAOpen() throws IOException {
        preferences = Preferences.userRoot().node("adopt-published-copy-test-" + System.nanoTime());
        recents = new RecentProjectsStore(preferences);
        ProjectLockManager lease = SaveTestSupport.lockManager("ben", lockFile -> {
            IOException failure = lockReadFailure;
            if (failure != null) {
                throw failure;
            }
            return Files.readString(lockFile);
        });
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)),
                recents, lease);
        ProjectMetadata created = manager.createProject("Song A", tmp);
        songA = created.projectPath();
        Files.createDirectories(songA.resolve("audio"));
        Files.write(songA.resolve("audio/take.wav"), new byte[] {1, 2, 3});
        project = new DawProject("Song A", AudioFormat.CD_QUALITY);
        project.setMetadata(created);
        project.createAudioTrack("Vox").addClip(new AudioClip("take", 0, 1, songA.resolve("audio/take.wav").toString()));
        saved(manager, project);
        project.markDirty();
    }

    @AfterEach
    void forgetRecents() throws BackingStoreException {
        lockReadFailure = null;
        await(manager.closeProject());
        preferences.removeNode();
    }

    @Test
    void theCopyBecomesTheOpenProjectWithoutAReloadUnderItsOwnLock() throws IOException {
        byte[] originalHead = Files.readAllBytes(songA.resolve("project.daw"));
        SaveAsOutcome.Published published = publish("Song B");
        ProjectManager.PreparedOpen lease = manager.prepareLockedAccess(published.folder());
        manager.closeProject();

        ProjectManager.AdoptedCopy adopted = manager.adoptPublishedCopy(published, lease, project);

        Path songB = published.folder();
        assertThat(adopted.metadata()).isEqualTo(published.metadata());
        assertThat(adopted.lockCheckFailure()).as("the copy's lock is still ours").isEmpty();
        assertThat(manager.getCurrentProject().projectPath()).isEqualTo(songB);
        assertThat(manager.getCurrentDawProject()).as("the same live model: nothing is reloaded").isSameAs(project);
        assertThat(manager.isReadOnly()).isFalse();
        assertThat(manager.getLockManager().currentLock()).as("the copy's lock is this session's").isPresent();
        assertThat(manager.getLockManager().status()).isEqualTo(LockStatus.HELD);
        assertThat(ProjectLockManager.peekLock(songB)).contains(manager.getLockManager().currentLock().get());
        assertThat(project.isDirty()).as("adopting changes nothing in the model; the caller marks it clean").isTrue();
        assertThat(manager.getRecentProjectPaths()).first().isEqualTo(songB);

        await(manager.whenRetiredLeasesReleased());
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME))
                .as("the original's lock is released by its close").doesNotExist();
        assertThat(Files.readAllBytes(songA.resolve("project.daw"))).as("the original's head stays at its last save")
                .isEqualTo(originalHead);
    }

    @Test
    void theAdoptedCopysSavesAndCheckpointsLandInTheCopyNeverInTheOriginal() throws IOException {
        byte[] originalHead = Files.readAllBytes(songA.resolve("project.daw"));
        SaveAsOutcome.Published published = publish("Song B");
        ProjectManager.PreparedOpen lease = manager.prepareLockedAccess(published.folder());
        manager.closeProject();
        manager.adoptPublishedCopy(published, lease, project);
        Path songB = published.folder();
        ProjectAssetReferences.rebase(project, songA, songB, published.formerRoots().heldFiles());
        project.setMetadata(published.metadata());

        project.createAudioTrack("Bass");
        SaveOutcome.Saved save = saved(manager, project);
        Path closing = await(manager.writeClosingCheckpoint(project));

        assertThat(Files.readString(songB.resolve("project.daw"))).contains("Bass");
        assertThat(save.checkpoint()).hasValueSatisfying(checkpoint -> assertThat(checkpoint).startsWith(songB));
        assertThat(closing).startsWith(songB.resolve("checkpoints"));
        assertThat(Files.readAllBytes(songA.resolve("project.daw"))).isEqualTo(originalHead);
    }

    @Test
    void aProjectStillOpenIsRefusedAndTheLeaseIsLeftToTheCaller() throws IOException {
        SaveAsOutcome.Published published = publish("Song B");
        ProjectManager.PreparedOpen lease = manager.prepareLockedAccess(published.folder());

        assertThatThrownBy(() -> manager.adoptPublishedCopy(published, lease, project))
                .isInstanceOf(IllegalStateException.class);

        assertThat(manager.getCurrentProject().projectPath()).isEqualTo(songA);
        lease.close();
        assertThat(published.folder().resolve(ProjectLockManager.LOCK_FILE_NAME))
                .as("the caller's close released the copy's lock").doesNotExist();
    }

    @Test
    void aLeaseOfAnotherFolderOrAReadOnlyOneIsRefusedAndNothingIsInstalled() throws IOException {
        SaveAsOutcome.Published published = publish("Song B");
        Path elsewhere = Files.createDirectory(tmp.resolve("Elsewhere"));
        ProjectManager.PreparedOpen other = manager.prepareLockedAccess(elsewhere);
        ProjectManager.PreparedOpen readOnly = manager.prepareReadOnlyOpen(published.folder());
        manager.closeProject();

        assertThatThrownBy(() -> manager.adoptPublishedCopy(published, other, project))
                .isInstanceOf(IOException.class).hasMessageContaining("belongs to");
        assertThatThrownBy(() -> manager.adoptPublishedCopy(published, readOnly, project))
                .isInstanceOf(IOException.class).hasMessageContaining("could not be locked");

        assertThat(manager.getCurrentProject()).isNull();
        assertThat(manager.getCurrentDawProject()).isNull();
        other.close();
        readOnly.close();
    }

    @Test
    void aLockTakenOverAfterTheCloseInstallsTheCopyUnderTheLostLockAndWritesNothingIntoIt() throws Exception {
        SaveAsOutcome.Published published = publish("Song B");
        Path songB = published.folder();
        ProjectManager.PreparedOpen lease = manager.prepareLockedAccess(songB);
        manager.closeProject();
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(songB);
        String foreignLock = Files.readString(songB.resolve(ProjectLockManager.LOCK_FILE_NAME));
        Map<String, FileTime> fromTheSteal = listing(songB);

        ProjectManager.AdoptedCopy adopted = manager.adoptPublishedCopy(published, lease, project);

        assertThat(adopted.lockCheckFailure()).as("Lost, with the holder").containsInstanceOf(LockStolenException.class);
        ProjectLock holder = ((LockStolenException) adopted.lockCheckFailure().orElseThrow()).currentHolder();
        assertThat(holder).isNotNull();
        assertThat(holder.user()).isEqualTo("alice");
        assertThat(holder.hostname()).isEqualTo("Studio-PC");
        assertThat(holder.pid()).isEqualTo(42L);
        assertTheCopyIsOpenUnderALeaseThatWritesNothing(songB, lease, fromTheSteal);
        assertThat(Files.readString(songB.resolve(ProjectLockManager.LOCK_FILE_NAME)))
                .as("the other session's lock survives").isEqualTo(foreignLock);
    }

    @Test
    void aLockFileRemovedAfterTheCloseInstallsTheCopyWithNoHolder() throws Exception {
        SaveAsOutcome.Published published = publish("Song B");
        Path songB = published.folder();
        ProjectManager.PreparedOpen lease = manager.prepareLockedAccess(songB);
        manager.closeProject();
        Files.delete(songB.resolve(ProjectLockManager.LOCK_FILE_NAME));
        Map<String, FileTime> fromTheSteal = listing(songB);

        ProjectManager.AdoptedCopy adopted = manager.adoptPublishedCopy(published, lease, project);

        assertThat(adopted.lockCheckFailure()).containsInstanceOf(LockStolenException.class);
        assertThat(((LockStolenException) adopted.lockCheckFailure().orElseThrow()).currentHolder())
                .as("no holder: the lock file is gone").isNull();
        assertTheCopyIsOpenUnderALeaseThatWritesNothing(songB, lease, fromTheSteal);
        assertThat(songB.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("nothing recreates it").doesNotExist();
    }

    @Test
    void aLockCheckThatCannotReadTheLockInstallsTheCopyAsUnverified() throws Exception {
        SaveAsOutcome.Published published = publish("Song B");
        Path songB = published.folder();
        ProjectManager.PreparedOpen lease = manager.prepareLockedAccess(songB);
        manager.closeProject();
        lockReadFailure = new IOException("the lock file could not be read");
        Map<String, FileTime> before = listing(songB);

        ProjectManager.AdoptedCopy adopted = manager.adoptPublishedCopy(published, lease, project);

        assertThat(adopted.lockCheckFailure()).as("Unverified: an IOException that is no steal")
                .hasValueSatisfying(failure -> assertThat(failure).isNotInstanceOf(LockStolenException.class)
                        .hasMessage("the lock file could not be read"));
        assertThat(manager.getCurrentProject().projectPath()).as("B installed").isEqualTo(songB);
        assertThat(manager.getLockManager()).isSameAs(lease.lock());
        assertThat(SaveTestSupport.failed(manager, project).cause()).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> await(manager.writeClosingCheckpoint(project)))
                .as("a forced checkpoint is refused by its generation's guard").isNotNull();
        await(manager.closeProject());
        await(manager.whenRetiredLeasesReleased());
        assertThat(listing(songB)).as("nothing was written to or deleted from the copy").isEqualTo(before);
        assertThat(manager.getRecentProjectPaths()).first().isEqualTo(songB);
    }

    @Test
    void aStealBeforeTheAdoptionEndsInTheSameStateAsAStealRightAfterIt() throws Exception {
        SaveAsOutcome.Published first = publish("Song B");
        ProjectManager.PreparedOpen firstLease = manager.prepareLockedAccess(first.folder());
        manager.closeProject();
        manager.adoptPublishedCopy(first, firstLease, project);
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(first.folder());
        assertThatThrownBy(() -> manager.getLockManager().verifyHeld()).isInstanceOf(LockStolenException.class);
        LockStatus adoptThenSteal = manager.getLockManager().status();
        boolean adoptThenStealLease = manager.getLockManager() == firstLease.lock();
        Throwable adoptThenStealSave = SaveTestSupport.failed(manager, project).cause();

        SaveAsOutcome.Published second = publish("Song C");
        ProjectManager.PreparedOpen secondLease = manager.prepareLockedAccess(second.folder());
        manager.closeProject();
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(second.folder());
        manager.adoptPublishedCopy(second, secondLease, project);

        assertThat(manager.getLockManager().status()).isEqualTo(adoptThenSteal).isEqualTo(LockStatus.STOLEN);
        assertThat(manager.getLockManager() == secondLease.lock()).isEqualTo(adoptThenStealLease).isTrue();
        assertThat(SaveTestSupport.failed(manager, project).cause()).isInstanceOf(LockStolenException.class)
                .hasSameClassAs(adoptThenStealSave);
    }

    @Test
    void aCopyPublishedButNotOpenedIsRememberedOffTheModelsThread() throws IOException {
        SaveAsOutcome.Published published = publish("Song B");

        manager.rememberRecentProject(published.folder());

        assertThat(manager.getRecentProjectPaths()).first().isEqualTo(published.folder());
        assertThat(manager.getCurrentProject().projectPath()).as("nothing else changed").isEqualTo(songA);
    }

    /**
     * The stolen-lock state, end to end (ruling 3 Q1): the copy is the open
     * project under the very lease that was prepared, its status STOLEN; its
     * save fails with {@link LockStolenException}; a forced checkpoint is
     * refused; and, from the steal on, nothing is written to or deleted from
     * the copy — through the adoption, the checkpoint attempt and the close
     * — and the copy is in Recent Projects.
     */
    private void assertTheCopyIsOpenUnderALeaseThatWritesNothing(Path songB, ProjectManager.PreparedOpen lease,
                                                                 Map<String, FileTime> fromTheSteal)
            throws Exception {
        assertThat(manager.getCurrentProject().projectPath()).as("B is the open project").isEqualTo(songB);
        assertThat(manager.getCurrentDawProject()).isSameAs(project);
        assertThat(manager.getLockManager()).as("the lease is the prepared one").isSameAs(lease.lock());
        assertThat(manager.getLockManager().status()).isEqualTo(LockStatus.STOLEN);
        assertThat(manager.getRecentProjectPaths()).first().isEqualTo(songB);
        assertThat(SaveTestSupport.failed(manager, project).cause()).isInstanceOf(LockStolenException.class);
        assertThatThrownBy(() -> await(manager.writeClosingCheckpoint(project)))
                .as("a forced checkpoint is refused by its generation's guard").isNotNull();
        assertThat(listing(songB)).as("nothing written after the adoption and the checkpoint attempt")
                .isEqualTo(fromTheSteal);
        await(manager.closeProject());
        await(manager.whenRetiredLeasesReleased());
        assertThat(listing(songB)).as("nor by the close and its release").isEqualTo(fromTheSteal);
    }

    /** Every file and folder under {@code folder}, by relative path, with its last-modified time. */
    private static Map<String, FileTime> listing(Path folder) throws IOException {
        Map<String, FileTime> entries = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path entry : walk.toList()) {
                entries.put(folder.relativize(entry).toString(), Files.getLastModifiedTime(entry));
            }
        }
        return entries;
    }

    private SaveAsOutcome.Published publish(String name) throws IOException {
        SaveAsPlan plan = manager.planSaveAs(manager.captureForSaveAs(project, name), tmp.resolve(name), () -> false);
        assertThat(plan).isInstanceOf(SaveAsPlan.Ready.class);
        SaveAsOutcome outcome = manager.publishCopy((SaveAsPlan.Ready) plan, SaveAsProgress.NONE, () -> false);
        assertThat(outcome).isInstanceOf(SaveAsOutcome.Published.class);
        return (SaveAsOutcome.Published) outcome;
    }
}
