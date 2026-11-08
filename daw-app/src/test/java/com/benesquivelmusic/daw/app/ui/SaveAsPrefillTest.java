package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.SaveAsPlan;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Save As's pre-filled target (the studio engineer's rulings R6
 * and Q6): the chooser opens on the original's parent with
 * {@code <folder name> copy} — or {@code <folder name>-takeover-<yyyyMMdd-HHmmss>}
 * after a takeover — the first free name with the {@code " <n>"} suffix; on
 * the workspace root with the project's name for a project never saved, and
 * when the parent is no writable folder or does not answer within the
 * bound, checked off the FX thread; the name the user types is sanitised
 * and becomes the project's name. A refused target reopens the chooser: a
 * taken name with the first free name in the same folder, a folder inside
 * the project with the typed name in the original's parent, not enough
 * space with the same folder and name.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SaveAsPrefillTest {

    /** 2026-10-27 14:32:05 UTC: the takeover stamp's moment. */
    private static final Instant STAMPED = Instant.parse("2026-10-27T14:32:05Z");

    @TempDir
    Path workspace;

    @TempDir
    Path workspaceRoot;

    private SaveFlowFixture fixture;

    @BeforeEach
    void songA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        runOnFx(() -> fixture.installSaveAs(fixture.saveAsJournal(), new SessionJournalEnding(fixture.saveAsJournal(),
                new com.benesquivelmusic.daw.core.session.SessionManager(), () -> null, () -> null, () -> null,
                fixture::openFolder, InstantSource.system()), InstantSource.fixed(STAMPED), ZoneOffset.UTC,
                () -> workspaceRoot));
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void aSaveAsOpensOnTheOriginalsParentWithTheCopysName() throws Exception {
        firstCallOf(SaveAsFlow.Prefill.COPY);

        assertThat(fixture.saveAsTargets.calls()).containsExactly(
                new SaveFlowFixture.SaveAsTargets.Call(workspace, "Song A copy"));
    }

    @Test
    void aTakenCopyNameGetsTheFirstFreeSuffix() throws Exception {
        Files.createDirectory(workspace.resolve("Song A copy"));
        Files.createDirectory(workspace.resolve("Song A copy 2"));

        firstCallOf(SaveAsFlow.Prefill.COPY);

        assertThat(fixture.saveAsTargets.calls().getFirst().name()).isEqualTo("Song A copy 3");
    }

    @Test
    void aTakeoverIsPrefilledWithTheStampedNameAndItsSuffix() throws Exception {
        firstCallOf(SaveAsFlow.Prefill.TAKEOVER);
        Files.createDirectory(workspace.resolve("Song A-takeover-20261027-143205"));
        firstCallOf(SaveAsFlow.Prefill.TAKEOVER);

        assertThat(fixture.saveAsTargets.calls()).extracting(SaveFlowFixture.SaveAsTargets.Call::name)
                .as("BACKUP_STAMP, local time (UTC here), Locale.ROOT digits").containsExactly(
                        "Song A-takeover-20261027-143205", "Song A-takeover-20261027-143205 2");
        assertThat(fixture.saveAsTargets.calls()).extracting(SaveFlowFixture.SaveAsTargets.Call::directory)
                .containsOnly(workspace);
    }

    @Test
    void aProjectNeverSavedOpensOnTheWorkspaceRootWithItsName() throws Exception {
        fixture.projectManager.closeProject().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        runOnFx(() -> fixture.project.set(new DawProject("Untitled Project", AudioFormat.CD_QUALITY)));

        firstCallOf(SaveAsFlow.Prefill.COPY);

        assertThat(fixture.saveAsTargets.calls()).containsExactly(
                new SaveFlowFixture.SaveAsTargets.Call(workspaceRoot, "Untitled Project"));
    }

    /**
     * The final prober's P09 (the studio engineer's final ruling F16): a
     * project never saved whose name is unsafe for a folder is prefilled with
     * the folder name Save As will create — its sanitised name.
     */
    @Test
    void aProjectNeverSavedWithANameUnsafeForAFolderIsPrefilledWithItsSanitisedName() throws Exception {
        fixture.projectManager.closeProject().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        runOnFx(() -> fixture.project.set(new DawProject("Mix: A/B", AudioFormat.CD_QUALITY)));

        firstCallOf(SaveAsFlow.Prefill.COPY);

        assertThat(fixture.saveAsTargets.calls()).containsExactly(
                new SaveFlowFixture.SaveAsTargets.Call(workspaceRoot, "Mix_ A_B"));
        assertThat(com.benesquivelmusic.daw.core.persistence.ProjectManager.sanitizeDirectoryName("Mix: A/B"))
                .as("the name Save As creates").isEqualTo("Mix_ A_B");
    }

    /**
     * The final prober's P12 (the studio engineer's final ruling F16): the
     * takeover stamp is for people, so it is the session's local time — here
     * in New York, four hours behind UTC on 27 October 2026.
     */
    @Test
    void aTakeoverStampIsInTheSessionsZone() throws Exception {
        java.time.ZoneId newYork = java.time.ZoneId.of("America/New_York");
        runOnFx(() -> fixture.installSaveAs(fixture.saveAsJournal(), new SessionJournalEnding(fixture.saveAsJournal(),
                new com.benesquivelmusic.daw.core.session.SessionManager(), () -> null, () -> null, () -> null,
                fixture::openFolder, InstantSource.system()), InstantSource.fixed(STAMPED), newYork,
                () -> workspaceRoot));

        firstCallOf(SaveAsFlow.Prefill.TAKEOVER);

        String local = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                .format(java.time.LocalDateTime.ofInstant(STAMPED, newYork));
        assertThat(local).as("fixture: a stamp that differs from UTC's").isEqualTo("20261027-103205");
        assertThat(fixture.saveAsTargets.calls()).extracting(SaveFlowFixture.SaveAsTargets.Call::name)
                .containsExactly("Song A-takeover-" + local);
    }

    @Test
    void anUnwritableParentFallsBackToTheWorkspaceRoot() throws Exception {
        runOnFx(() -> fixture.saveAsFlow().setPrefillChecks(folder -> !folder.equals(workspace),
                SaveAsFlow.PREFILL_BOUND));

        firstCallOf(SaveAsFlow.Prefill.COPY);

        assertThat(fixture.saveAsTargets.calls()).containsExactly(
                new SaveFlowFixture.SaveAsTargets.Call(workspaceRoot, "Song A copy"));
    }

    @Test
    void aParentThatDoesNotAnswerWithinTheBoundFallsBackToTheWorkspaceRoot() throws Exception {
        CountDownLatch answers = new CountDownLatch(1);
        runOnFx(() -> fixture.saveAsFlow().setPrefillChecks(folder -> {
            if (folder.equals(workspace)) {
                try {
                    // The original's volume answers after three seconds; the bound is far shorter.
                    answers.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        }, Duration.ofMillis(100)));
        try {
            firstCallOf(SaveAsFlow.Prefill.COPY);
        } finally {
            answers.countDown();
        }

        assertThat(fixture.saveAsTargets.calls()).containsExactly(
                new SaveFlowFixture.SaveAsTargets.Call(workspaceRoot, "Song A copy"));
    }

    /**
     * Stage-5 review N5: every check of the pre-fill shares one bound — a
     * parent that takes all of it leaves no time for another check, so the
     * chooser opens with the name as it is (a taken one is refused there,
     * ruling Q6) instead of waiting a second bound for the free name.
     */
    @Test
    void theChecksShareOneBoundSoASlowParentLeavesNoSecondWait() throws Exception {
        Files.createDirectory(workspaceRoot.resolve("Song A copy"));
        CountDownLatch answers = new CountDownLatch(1);
        runOnFx(() -> fixture.saveAsFlow().setPrefillChecks(folder -> {
            if (folder.equals(workspace)) {
                try {
                    answers.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        }, Duration.ofMillis(100)));
        try {
            firstCallOf(SaveAsFlow.Prefill.COPY);
        } finally {
            answers.countDown();
        }

        assertThat(fixture.saveAsTargets.calls()).as("the workspace root, checked at once; no free-name check after"
                + " the bound").containsExactly(new SaveFlowFixture.SaveAsTargets.Call(workspaceRoot, "Song A copy"));
    }

    /**
     * The app fix pass 1 review, S1 (the mirror of the shared bound): a
     * parent that answers is never held up by a workspace root that does not
     * — its answer is waited for only when the parent is not a writable
     * folder — so the free name is still checked within the bound (R6).
     */
    @Test
    void aParentThatAnswersIsNeverHeldUpByASlowWorkspaceRoot() throws Exception {
        Files.createDirectory(workspace.resolve("Song A copy"));
        CountDownLatch answers = new CountDownLatch(1);
        runOnFx(() -> fixture.saveAsFlow().setPrefillChecks(folder -> {
            if (folder.equals(workspaceRoot)) {
                try {
                    answers.await(GUARD.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        }, Duration.ofSeconds(1)));
        try {
            firstCallOf(SaveAsFlow.Prefill.COPY);
        } finally {
            answers.countDown();
        }

        assertThat(fixture.saveAsTargets.calls()).as("the parent, and its first free name")
                .containsExactly(new SaveFlowFixture.SaveAsTargets.Call(workspace, "Song A copy 2"));
    }

    /**
     * Stage-5 review N9: the pre-fill is named after the folder, never the
     * project's own name, which may differ (R6).
     */
    @Test
    void thePrefillIsNamedAfterTheFolderNotTheProject() throws Exception {
        runOnFx(() -> fixture.project.get().setName("Mix"));

        firstCallOf(SaveAsFlow.Prefill.COPY);
        firstCallOf(SaveAsFlow.Prefill.TAKEOVER);

        assertThat(fixture.saveAsTargets.calls()).extracting(SaveFlowFixture.SaveAsTargets.Call::name)
                .containsExactly("Song A copy", "Song A-takeover-20261027-143205");
    }

    @Test
    void theTypedNameIsSanitisedAndBecomesTheProjectsName() throws Exception {
        fixture.saveAsTargets.choose(workspace.resolve("Song A (final) #2"));

        assertThat(await(start(SaveAsFlow.Prefill.COPY)))
                .isEqualTo(new SaveAsFlow.Published(workspace.resolve("Song A _final_ _2")));
        assertThat(onFx(() -> fixture.project.get().getName())).isEqualTo("Song A _final_ _2");
    }

    @Test
    void aRefusedTargetReopensTheChooserAsRulingQ6Says() throws Exception {
        Files.createDirectories(workspace.resolve("Taken").resolve("notes"));
        AtomicReference<Integer> plans = new AtomicReference<>(0);
        runOnFx(() -> fixture.saveAsFlow().setPlanner((capture, target, cancelled) -> {
            plans.set(plans.get() + 1);
            return target.getFileName().toString().equals("Big")
                    ? new SaveAsPlan.NotEnoughSpace(5L << 30, 1L << 30, target.getRoot())
                    : fixture.projectManager.planSaveAs(capture, target, cancelled);
        }));
        fixture.saveAsTargets.choose(workspace.resolve("Taken"))
                .choose(fixture.songA.resolve("Inner"))
                .choose(workspace.resolve("Big"))
                .cancel();

        assertThat(await(start(SaveAsFlow.Prefill.COPY))).isEqualTo(new SaveAsFlow.ChooserCancelled());

        assertThat(fixture.saveAsTargets.calls()).containsExactly(
                new SaveFlowFixture.SaveAsTargets.Call(workspace, "Song A copy"),
                new SaveFlowFixture.SaveAsTargets.Call(workspace, "Taken 2"),
                new SaveFlowFixture.SaveAsTargets.Call(workspace, "Inner"),
                new SaveFlowFixture.SaveAsTargets.Call(workspace, "Big"));
        assertThat(fixture.saveAsRefusals).hasSize(3);
        assertThat(fixture.saveAsRefusals.get(2)).isEqualTo("Save As needs 5.0 GB free on " + workspace.getRoot()
                + ", and it has 1.0 GB. Choose another location.");
        assertThat(plans.get()).isEqualTo(3);
    }

    private void firstCallOf(SaveAsFlow.Prefill prefill) throws Exception {
        fixture.saveAsTargets.cancel();
        assertThat(await(start(prefill))).isEqualTo(new SaveAsFlow.ChooserCancelled());
    }

    private CompletionStage<SaveAsFlow.SaveAsResult> start(SaveAsFlow.Prefill prefill) throws Exception {
        AtomicReference<CompletionStage<SaveAsFlow.SaveAsResult>> stage = new AtomicReference<>();
        runOnFx(() -> stage.set(fixture.saveAsFlow().start(prefill)));
        return stage.get();
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(GUARD.toMillis() * 2, TimeUnit.MILLISECONDS);
    }
}
