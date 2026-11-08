package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.JournalEnding.JournalEnd;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the studio engineer's ruling R12 #6 and Q4.6 (Persistence
 * Integrity book §5.2, "exit aborts visibly if seal cannot be written"): a
 * journal that cannot be sealed opens the closing surface at once — the
 * exit's text with Quit anyway | Don't quit, Close Project's with Close
 * anyway | Keep project open — and nothing is still writing. Quit or Close
 * anyway release the folder, its journal left unsealed; Don't quit keeps the
 * project open, its journaling paused, and a later exit finds the journal
 * closed unsealed, rewrites no manifest, and goes on. After a Discard no
 * dialog shows: the failure is logged and the exit goes on.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SealFailureAtExitTest {

    private static final String CAUSE = "the journal disk refused the seal";

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private final AtomicBoolean failTheSeal = new AtomicBoolean(true);

    private void songA() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT, true, this::sealThatThrowsOnce);
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void anExitWhoseSealFailedOffersQuitAnywayWhichReleasesTheFolderWithTheJournalUnsealed() throws Exception {
        songA();
        fixture.closeBox();
        fixture.await(() -> fixture.surface() != null && fixture.surface().isOpen()
                && fixture.surface().offerButton().isVisible(), "the seal failure's surface");

        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(surface::noteText)).isEqualTo("The project is saved, but its journal could not be sealed: "
                + CAUSE + ". If you quit anyway, the next open may offer to recover this session.");
        assertThat(onFx(() -> surface.offerButton().getText())).isEqualTo("Quit anyway");
        assertThat(onFx(() -> surface.keepButton().getText())).isEqualTo("Don't quit");
        assertThat(onFx(() -> surface.keepButton().isDisabled())).isFalse();
        assertThat(onFx(() -> surface.offerButton().isDefaultButton() || surface.keepButton().isDefaultButton()))
                .as("neither is the default").isFalse();
        assertThat(fixture.stepNames()).contains("SEAL FAILED").doesNotContain("RELEASE DONE");

        runOnFx(() -> surface.offerButton().fire());
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.stepNames()).containsSubsequence("SEAL FAILED", "RELEASE DONE", "DISPOSE DONE",
                "HIDE DONE");
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("released").doesNotExist();
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA)))
                .as("the journal is left unsealed").noneMatch(JournalRecord::isSessionSeal);
    }

    @Test
    void closeProjectsSealFailureOffersCloseAnywayAndKeepProjectOpenKeepsItWithItsJournalingPaused()
            throws Exception {
        songA();
        fixture.closeProject();
        fixture.await(() -> fixture.surface() != null && fixture.surface().isOpen()
                && fixture.surface().offerButton().isVisible(), "the seal failure's surface");

        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(surface::noteText)).isEqualTo("The project is saved, but its journal could not be sealed: "
                + CAUSE + ". If you close it anyway, the next open may offer to recover this session.");
        assertThat(onFx(() -> surface.offerButton().getText())).isEqualTo("Close anyway");
        assertThat(onFx(() -> surface.keepButton().getText())).isEqualTo("Keep project open");

        runOnFx(() -> surface.keepButton().fire());
        fixture.await(() -> !surface.isOpen(), "the close ended");
        assertThat(fixture.save.notifications()).extracting(NotificationEntry::message).containsExactly(
                "Close Project cancelled. Journaled persistence is paused until this project is reopened.");
        assertThat(fixture.save.currentProjectIs(fixture.songA)).as("the project stays open").isTrue();
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();
    }

    @Test
    void dontQuitAfterASealFailureKeepsTheProjectOpenAndALaterExitRewritesNoManifest() throws Exception {
        songA();
        fixture.closeBox();
        fixture.await(() -> fixture.surface() != null && fixture.surface().offerButton().isVisible(),
                "the seal failure's surface");
        runOnFx(() -> fixture.surface().keepButton().fire());
        fixture.await(() -> !fixture.surface().isOpen(), "the exit was cancelled");
        assertThat(fixture.save.notifications()).extracting(NotificationEntry::message).containsExactly(
                "Exit cancelled. Journaled persistence is paused until this project is reopened.");
        assertThat(fixture.journal.closedUnsealed(fixture.songA)).isTrue();
        byte[] manifest = manifestOf(fixture.songA);

        fixture.steps.clear();
        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(manifestOf(fixture.songA)).as("closed unsealed: SEAL rewrites no manifest").isEqualTo(manifest);
        assertThat(fixture.stepNames()).contains("SEAL DONE", "RELEASE DONE", "HIDE DONE");
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA)))
                .as("it certified nothing").noneMatch(JournalRecord::isSessionSeal);
    }

    @Test
    void afterADiscardASealFailureShowsNoDialogAndTheExitGoesOn() throws Exception {
        songA();
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Discard and close";

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.stepNames()).containsSubsequence("SEAL FAILED", "RELEASE DONE", "HIDE DONE");
        assertThat(fixture.surfaces).as("no dialog").allSatisfy(surface ->
                assertThat(onFx(() -> surface.offerButton().isVisible())).isFalse());
        fixture.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(),
                TimeUnit.MILLISECONDS);
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
    }

    /**
     * {@code real}, whose first SEAL fails as the coordinator's does when the
     * disk refuses the seal: the journal is closed unsealed and the failure
     * thrown (the {@code SaveAsFlowTest} idiom); later ends are the real ones.
     */
    private JournalEnding sealThatThrowsOnce(JournalEnding real) {
        return new JournalEnding() {
            @Override
            public void detach() {
                real.detach();
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                if (mode != JournalEnd.SEAL || !failTheSeal.compareAndSet(true, false)) {
                    return real.prepare(mode);
                }
                Runnable closedUnsealed = real.prepare(JournalEnd.DRAIN_UNSEALED);
                return () -> {
                    closedUnsealed.run();
                    throw new UncheckedIOException("Project journal could not be sealed", new IOException(CAUSE));
                };
            }
        };
    }

    private static byte[] manifestOf(Path folder) throws IOException {
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
