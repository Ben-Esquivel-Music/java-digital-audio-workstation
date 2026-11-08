package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.JournalEnding.JournalEnd;
import com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.core.session.WorkingSession;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;

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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, how a close ends the journal ({@link SessionJournalEnding}; the
 * studio engineer's rulings R3, R7 and ruling 2 OQ-1, constraint 2): over a
 * real journal coordinator, the journal of the folder being closed is
 * sealed into that folder's session, or drained unsealed — with or without
 * a manifest — while a journal the coordinator holds for another folder is
 * never touched, whatever the end. A folder whose journal was drained is
 * never certified clean by a later seal of its manifest alone; a session
 * still being opened is waited for on the worker. The coordinator's own
 * close facts are pinned too: a seal's manifest names the segment the seal
 * landed in, a close for no folder never waits behind another close, and
 * the closing checkpoint's trigger (i) follows the records offered.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SessionJournalEndingTest {

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;
    private DefaultEventBus bus;
    private ProjectJournalCoordinator journal;
    private final SessionManager sessions = new SessionManager();
    private final List<WorkingSession> sealed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void songAWithItsJournalOpen() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        bus = new DefaultEventBus();
        journal = new ProjectJournalCoordinator(fixture.progress, onFx(NotificationBar::new), fixture.dispatcher,
                () -> true, () -> bus, fixture.checkpoints, sessions, InstantSource.system());
        journal.setSessionSealedSink((_, session) -> sealed.add(session));
        journal.openFor(fixture.songA);
    }

    @AfterEach
    void close() throws Exception {
        try {
            journal.drainWithoutSeal(null);
            fixture.close();
        } finally {
            bus.close();
        }
    }

    @Test
    void aSealOfTheJournalsOwnFolderSealsItIntoThatFoldersSession() throws Exception {
        WorkingSession session = sessions.openSession(fixture.songA, Instant.now()).current();
        bus.publish(new TransportEvent.Started(0L, Instant.now()));

        prepare(ending(() -> session, () -> fixture.songA, () -> null, () -> fixture.songA), JournalEnd.SEAL).run();

        assertThat(journal.isOpen()).isFalse();
        assertThat(lastRecordOf(fixture.songA).isSessionSeal()).isTrue();
        assertThat(sealed).hasSize(1);
        assertThat(sealed.getFirst().id()).isEqualTo(session.id());
        assertThat(journal.closedUnsealed(fixture.songA)).isFalse();
    }

    @Test
    void aJournalTheCoordinatorHoldsForAnotherFolderIsNeverTouchedWhateverTheEnd() throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        WorkingSession sessionOfB = sessions.openSession(songB, Instant.now()).current();
        JournalEnding ofB = ending(() -> sessionOfB, () -> songB, () -> null, () -> songB);

        for (JournalEnd mode : JournalEnd.values()) {
            prepare(ofB, mode).run();
        }

        assertThat(journal.currentProjectDirectory()).as("Song A's journal stays open").isEqualTo(fixture.songA);
        assertThat(lastRecordOf(fixture.songA).isSessionSeal()).isFalse();
        assertThat(sealed).as("nothing of Song A's journal folded into a session").isEmpty();
        assertThat(sessions.history(songB)).as("Song B's session manifest closed alone")
                .anySatisfy(session -> assertThat(session.endTime()).isNotNull());
    }

    @Test
    void aFolderWhoseJournalWasDrainedIsNeverCertifiedCleanByItsManifestAlone() throws Exception {
        WorkingSession session = sessions.openSession(fixture.songA, Instant.now()).current();
        JournalEnding ofA = ending(() -> session, () -> fixture.songA, () -> null, () -> fixture.songA);
        byte[] manifest = manifestsOf(fixture.songA);

        prepare(ofA, JournalEnd.DRAIN_WITHOUT_MANIFEST).run();
        assertThat(journal.isOpen()).isFalse();
        assertThat(journal.closedUnsealed(fixture.songA)).isTrue();
        assertThat(manifestsOf(fixture.songA)).as("no manifest written").isEqualTo(manifest);

        prepare(ofA, JournalEnd.SEAL).run();

        assertThat(manifestsOf(fixture.songA)).as("SEAL's manifest-only branch certifies nothing").isEqualTo(manifest);
        assertThat(lastRecordOf(fixture.songA).isSessionSeal()).isFalse();
        // The next open's recovery retires the unsealed segments; a new journal of the folder then clears the fact.
        JournalState.clear(ProjectContext.forProject(fixture.songA));
        journal.openFor(fixture.songA);
        assertThat(journal.currentProjectDirectory()).isEqualTo(fixture.songA);
        assertThat(journal.closedUnsealed(fixture.songA)).as("a new journal of the folder clears it").isFalse();
    }

    @Test
    void aSessionStillBeingOpenedIsWaitedForOnTheWorker() throws Exception {
        CompletableFuture<Optional<WorkingSession>> opening = new CompletableFuture<>();
        Runnable seal = prepare(ending(() -> null, () -> null,
                () -> new SessionJournalEnding.SessionOpening(fixture.songA, 1L, opening), () -> fixture.songA),
                JournalEnd.SEAL);
        CompletableFuture<Void> worker = CompletableFuture.runAsync(seal);
        assertThat(worker).as("waiting for the session's open").isNotDone();

        opening.complete(Optional.of(sessions.openSession(fixture.songA, Instant.now()).current()));
        worker.get(GUARD.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(sealed).as("the segments folded into the session that opened").hasSize(1);
    }

    @Test
    void withNoFolderOpenNoJournalIsEnded() throws Exception {
        prepare(ending(() -> null, () -> null, () -> null, () -> null), JournalEnd.SEAL).run();

        assertThat(journal.currentProjectDirectory()).isEqualTo(fixture.songA);
    }

    @Test
    void aSealsManifestNamesTheSegmentTheSealLandedInAfterTheMarkersRotation() throws Exception {
        WorkingSession session = sessions.openSession(fixture.songA, Instant.now()).current();
        bus.publish(new TransportEvent.Started(0L, Instant.now()));
        // A checkpoint of the running generation records its capture boundary: the final drain then rotates.
        fixture.projectManager.writeClosingCheckpoint(fixture.project.get()).get(GUARD.toMillis(),
                TimeUnit.MILLISECONDS);

        journal.closeCurrentFor(fixture.songA, session);

        assertThat(sealed).hasSize(1);
        assertThat(sealed.getFirst().journalSegments()).as("COV-S-4: read after the seal")
                .containsExactlyInAnyOrderElementsOf(segmentsOf(fixture.songA)).hasSizeGreaterThan(1);
        assertThat(lastRecordOf(fixture.songA).isSessionSeal()).isTrue();
    }

    @Test
    void aCloseForNoFolderNeverWaitsBehindAnotherClose() throws Exception {
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        journal.setSessionSealedSink((_, _) -> {
            inside.countDown();
            try {
                release.await(GUARD.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        WorkingSession session = sessions.openSession(fixture.songA, Instant.now()).current();
        bus.publish(new TransportEvent.Started(0L, Instant.now()));
        CompletableFuture<Void> heldClose = CompletableFuture.runAsync(
                () -> journal.closeCurrentFor(fixture.songA, session));
        try {
            assertThat(inside.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("a close holds the lock").isTrue();

            CompletableFuture.runAsync(() -> {
                journal.closeCurrentFor(null, null);
                journal.drainWithoutSealFor(null, null);
            }).get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        heldClose.get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void theClosingCheckpointsTriggerFollowsTheRecordsOfferedOutsideEveryCapture() throws Exception {
        assertThat(journal.hasRecordsOutsideACapture()).as("a fresh journal").isFalse();

        bus.publish(new TransportEvent.Started(0L, Instant.now()));
        assertThat(journal.hasRecordsOutsideACapture()).as("a record after the last boundary").isTrue();

        fixture.projectManager.writeClosingCheckpoint(fixture.project.get()).get(GUARD.toMillis(),
                TimeUnit.MILLISECONDS);
        assertThat(journal.hasRecordsOutsideACapture()).as("covered by the checkpoint's marker").isFalse();
        journal.drainWithoutSeal(null);
        assertThat(journal.hasRecordsOutsideACapture()).as("no journal open").isFalse();
    }

    private JournalEnding ending(Supplier<WorkingSession> active, Supplier<Path> activeDir,
                                 Supplier<SessionJournalEnding.SessionOpening> opening, Supplier<Path> folder) {
        return new SessionJournalEnding(journal, sessions, active, activeDir, opening, folder, InstantSource.system());
    }

    /** Prepares {@code mode} on the FX thread, as a close does; the returned work runs on the caller. */
    private static Runnable prepare(JournalEnding ending, JournalEnd mode) throws Exception {
        AtomicReference<Runnable> work = new AtomicReference<>();
        SaveFlowFixture.runOnFx(() -> work.set(ending.prepare(mode)));
        return work.get();
    }

    private static JournalRecord lastRecordOf(Path folder) throws IOException {
        List<JournalRecord> records = JournalState.records(ProjectContext.forProject(folder));
        assertThat(records).isNotEmpty();
        return records.getLast();
    }

    private static List<String> segmentsOf(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder.resolve("journal"))) {
            return files.map(file -> file.getFileName().toString()).filter(name -> name.startsWith("segment-"))
                    .sorted().toList();
        }
    }

    private static byte[] manifestsOf(Path folder) throws IOException {
        Path manifests = folder.resolve("sessions");
        if (!Files.isDirectory(manifests)) {
            return new byte[0];
        }
        try (Stream<Path> files = Files.list(manifests)) {
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            for (Path file : files.sorted().toList()) {
                all.write(Files.readAllBytes(file));
            }
            return all.toByteArray();
        }
    }
}
