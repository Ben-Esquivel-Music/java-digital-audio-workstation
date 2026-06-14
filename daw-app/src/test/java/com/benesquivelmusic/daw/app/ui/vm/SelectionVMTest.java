package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.EditTool;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 292 — verifies {@link SelectionVM}: the UI-authoritative selection model
 * (Control Synchronization Design Book §3.3, §5.5, §5.7). Unlike the model-derived
 * VMs it carries no {@link FxDispatcher} — its writer methods set the read-only
 * property wrappers synchronously on the calling FX thread — so every interaction
 * here runs inside {@link #runOnFx(Runnable)} and reads back through a thread-safe
 * holder, never asserting on the FX thread itself.
 *
 * <p>{@link #clampTo(ProjectVM)} is the cascade's step-5 restore-and-clamp: a
 * selected track absent from the newly-loaded project is cleared so no surface
 * binds a stale target.</p>
 */
class SelectionVMTest {

    private static final long TIMEOUT_SECONDS = 5;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void selectTrackUpdatesSelectedTrackProperty() throws InterruptedException {
        SelectionVM vm = new SelectionVM();
        try {
            DawProject project = new DawProject("P", AudioFormat.CD_QUALITY);
            Track trackA = project.createAudioTrack("A");

            Track observed = callOnFx(() -> {
                vm.selectTrack(trackA);
                return vm.getSelectedTrack();
            });

            assertThat(observed)
                    .as("selectTrack sets the exact track instance")
                    .isSameAs(trackA);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void defaultToolIsPointer() throws InterruptedException {
        SelectionVM vm = new SelectionVM();
        try {
            EditTool tool = callOnFx(vm::getTool);
            assertThat(tool).isEqualTo(EditTool.POINTER);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void setToolUpdatesTool() throws InterruptedException {
        SelectionVM vm = new SelectionVM();
        try {
            EditTool tool = callOnFx(() -> {
                vm.setTool(EditTool.PENCIL);
                return vm.getTool();
            });
            assertThat(tool).isEqualTo(EditTool.PENCIL);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void clearResetsSelectionButKeepsTool() throws InterruptedException {
        SelectionVM vm = new SelectionVM();
        try {
            DawProject project = new DawProject("P", AudioFormat.CD_QUALITY);
            Track trackA = project.createAudioTrack("A");

            SelectionSnapshot snapshot = callOnFx(() -> {
                vm.selectTrack(trackA);
                vm.selectClip(new Object());
                vm.setTool(EditTool.PENCIL);
                vm.clear();
                return new SelectionSnapshot(
                        vm.getSelectedTrack(), vm.getSelectedClip(), vm.getTool());
            });

            assertThat(snapshot.track()).as("clear() nulls the selected track").isNull();
            assertThat(snapshot.clip()).as("clear() nulls the selected clip").isNull();
            assertThat(snapshot.tool())
                    .as("clear() leaves the edit tool untouched")
                    .isEqualTo(EditTool.PENCIL);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void selectedTrackPropertyIsReadOnly() {
        SelectionVM vm = new SelectionVM();
        try {
            assertThat(vm.selectedTrackProperty()).isInstanceOf(ReadOnlyObjectProperty.class);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void clampToClearsSelectedTrackAbsentFromProject() throws InterruptedException {
        SelectionVM vm = new SelectionVM();
        ProjectVM projectVm2 = null;
        try {
            // trackA belongs to p1; p2 never contains it.
            DawProject p1 = new DawProject("P1", AudioFormat.CD_QUALITY);
            Track trackA = p1.createAudioTrack("A");
            DawProject p2 = new DawProject("P2", AudioFormat.CD_QUALITY);

            projectVm2 = constructProjectVmOnFx(p2);
            ProjectVM clampTarget = projectVm2;

            Track observed = callOnFx(() -> {
                vm.selectTrack(trackA);
                vm.clampTo(clampTarget);
                return vm.getSelectedTrack();
            });

            assertThat(observed)
                    .as("a selected track absent from the loaded project is cleared")
                    .isNull();
        } finally {
            if (projectVm2 != null) {
                projectVm2.dispose();
            }
            vm.dispose();
        }
    }

    @Test
    void clampToKeepsSelectedTrackPresentInProject() throws InterruptedException {
        SelectionVM vm = new SelectionVM();
        ProjectVM projectVm = null;
        try {
            // trackA belongs to the same project the ProjectVM mirrors.
            DawProject project = new DawProject("P", AudioFormat.CD_QUALITY);
            Track trackA = project.createAudioTrack("A");

            projectVm = constructProjectVmOnFx(project);
            ProjectVM clampTarget = projectVm;

            Track observed = callOnFx(() -> {
                vm.selectTrack(trackA);
                vm.clampTo(clampTarget);
                return vm.getSelectedTrack();
            });

            assertThat(observed)
                    .as("a selected track still present in the project survives the clamp")
                    .isSameAs(trackA);
        } finally {
            if (projectVm != null) {
                projectVm.dispose();
            }
            vm.dispose();
        }
    }

    // ── FX-thread helpers (capture results into holders, assert on the caller) ──

    /** ProjectVM touches FX collections in its constructor, so build it on the FX thread. */
    private static ProjectVM constructProjectVmOnFx(DawProject project) throws InterruptedException {
        return callOnFx(() -> new ProjectVM(project, new FxDispatcher()));
    }

    private record SelectionSnapshot(Track track, Object clip, EditTool tool) {
    }

    /**
     * Runs {@code work} on the FX thread, returning its result to the calling
     * thread (so the assertion happens off the FX thread and is never swallowed).
     */
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
        rethrow(thrown.get());
        return result.get();
    }

    private static void rethrow(Throwable thrown) {
        if (thrown instanceof RuntimeException re) {
            throw re;
        }
        if (thrown instanceof Error e) {
            throw e;
        }
        if (thrown != null) {
            throw new AssertionError("FX work threw a checked exception", thrown);
        }
    }

    @FunctionalInterface
    private interface FxCallable<T> {
        T call();
    }
}
