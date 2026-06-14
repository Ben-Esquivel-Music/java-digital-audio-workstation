package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.ProjectLoadCascade.Phase;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 292 — verifies {@link ProjectLoadCascade}: the §1.6 flicker fix that
 * replaces an ad-hoc load sequence with a single declared {@link Phase} order a
 * test can pin (Control Synchronization Design Book §5.7). The ordering proofs are
 * pure (no toolkit), and the step-5 clamp proof drives a real
 * {@link SelectionVM#clampTo(ProjectVM)} on the FX thread.
 */
class ProjectLoadCascadeOrderTest {

    private static final long TIMEOUT_SECONDS = 5;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        // Needed only by clampsSelectionInStep5; harmless for the pure-order tests.
        FxTestSupport.startToolkit();
    }

    @Test
    void runsPhasesInDeclaredOrder() {
        List<Phase> recorded = new ArrayList<>();
        ProjectLoadCascade.Builder builder = ProjectLoadCascade.builder();
        for (Phase phase : Phase.values()) {
            builder.on(phase, () -> recorded.add(phase));
        }
        ProjectLoadCascade cascade = builder.build();

        cascade.run();

        List<Phase> expected = List.of(
                Phase.TEAR_DOWN, Phase.BUILD_VMS, Phase.REBUILD_VIEWS,
                Phase.BIND_CONTINUOUS, Phase.RESTORE_SELECTION, Phase.ANNOUNCE);
        assertThat(recorded)
                .as("each phase's injected step runs in the declared order")
                .containsExactlyElementsOf(expected);
        assertThat(cascade.executedPhases())
                .as("executedPhases() mirrors the declared order")
                .containsExactlyElementsOf(expected);
    }

    @Test
    void buildVmsRunsBeforeRebuildViews() {
        AtomicBoolean vmsBuilt = new AtomicBoolean(false);
        AtomicBoolean observedBuiltBeforeViews = new AtomicBoolean(false);

        ProjectLoadCascade cascade = ProjectLoadCascade.builder()
                .on(Phase.BUILD_VMS, () -> vmsBuilt.set(true))
                .on(Phase.REBUILD_VIEWS, () -> observedBuiltBeforeViews.set(vmsBuilt.get()))
                .build();

        cascade.run();

        assertThat(observedBuiltBeforeViews.get())
                .as("REBUILD_VIEWS observes the VMs already built — the anti-flicker guarantee")
                .isTrue();
    }

    @Test
    void missingPhaseStepStillRecorded() {
        ProjectLoadCascade cascade = ProjectLoadCascade.builder()
                .on(Phase.TEAR_DOWN, () -> { })
                .on(Phase.ANNOUNCE, () -> { })
                .build();

        cascade.run();

        assertThat(cascade.executedPhases())
                .as("a phase with no step is still a sequenced no-op slot")
                .containsExactly(
                        Phase.TEAR_DOWN, Phase.BUILD_VMS, Phase.REBUILD_VIEWS,
                        Phase.BIND_CONTINUOUS, Phase.RESTORE_SELECTION, Phase.ANNOUNCE);
    }

    @Test
    void clampsSelectionInStep5() throws InterruptedException {
        SelectionVM selection = new SelectionVM();
        AtomicReference<ProjectVM> newVmRef = new AtomicReference<>();
        try {
            // trackA belongs to p1; the freshly-loaded p2 never contains it.
            DawProject p1 = new DawProject("P1", AudioFormat.CD_QUALITY);
            Track trackA = p1.createAudioTrack("A");
            DawProject p2 = new DawProject("P2", AudioFormat.CD_QUALITY);

            // Run the cascade on FX (SelectionVM/ProjectVM construction), capturing
            // both the post-clamp selection and the executed phases into holders so
            // every assertion happens on the calling thread.
            ClampOutcome outcome = callOnFx(() -> {
                ProjectVM newVm = new ProjectVM(p2, new FxDispatcher());
                newVmRef.set(newVm);
                selection.selectTrack(trackA);

                ProjectLoadCascade cascade = ProjectLoadCascade.builder()
                        .on(Phase.RESTORE_SELECTION, () -> selection.clampTo(newVm))
                        .build();
                cascade.run();
                return new ClampOutcome(selection.getSelectedTrack(), cascade.executedPhases());
            });

            assertThat(outcome.selectedTrack())
                    .as("step 5 clears a selection absent from the newly-loaded project")
                    .isNull();
            assertThat(outcome.executedPhases().indexOf(Phase.RESTORE_SELECTION))
                    .as("RESTORE_SELECTION runs before ANNOUNCE")
                    .isLessThan(outcome.executedPhases().indexOf(Phase.ANNOUNCE));
        } finally {
            ProjectVM newVm = newVmRef.get();
            if (newVm != null) {
                callOnFx(() -> {
                    newVm.dispose();
                    return null;
                });
            }
            selection.dispose();
        }
    }

    private record ClampOutcome(Track selectedTrack, List<Phase> executedPhases) {
    }

    // ── FX-thread helper (capture into a holder, assert on the caller) ──────────

    private static <T> T callOnFx(FxCallable<T> work) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        Throwable t = thrown.get();
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        if (t != null) {
            throw new AssertionError("FX work threw a checked exception", t);
        }
        return result.get();
    }

    @FunctionalInterface
    private interface FxCallable<T> {
        T call();
    }
}
