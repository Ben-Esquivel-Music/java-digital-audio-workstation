package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 292 — verifies {@link ProjectVM}: the read-only projection of a
 * {@link DawProject} the project-level UI binds to (Control Synchronization
 * Design Book §3.1, §3.2, §3.3, §4.3). The VM seeds synchronously at construction
 * but marshals every later core signal through {@link FxDispatcher#onFx(Runnable)},
 * so a mutation is observed only after {@link #flushFx()}; values are read through
 * a {@link #callOnFx(FxCallable)} capture, never asserted on the FX thread. The VM
 * is constructed on the FX thread because its constructor seeds the backing
 * {@code ObservableList}.
 */
class ProjectVMTest {

    private static final long TIMEOUT_SECONDS = 5;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void seedsFromProject() throws InterruptedException {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        ProjectVM vm = constructOnFx(project);
        try {
            ProjectSnapshot snapshot = snapshotOf(vm);
            assertThat(snapshot.name()).as("name seeds from the project").isEqualTo("Song");
            assertThat(snapshot.dirty()).as("a fresh project is clean").isFalse();
            assertThat(snapshot.tracks()).as("a fresh project has no tracks").isEmpty();
        } finally {
            disposeOnFx(vm);
        }
    }

    @Test
    void nameFollowsSetName() throws InterruptedException {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        ProjectVM vm = constructOnFx(project);
        try {
            project.setName("Renamed");
            flushFx();
            assertThat(snapshotOf(vm).name())
                    .as("the name property follows a core NAME change")
                    .isEqualTo("Renamed");
        } finally {
            disposeOnFx(vm);
        }
    }

    @Test
    void dirtyFollowsMarkDirtyThenClean() throws InterruptedException {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        ProjectVM vm = constructOnFx(project);
        try {
            project.markDirty();
            flushFx();
            assertThat(snapshotOf(vm).dirty())
                    .as("the dirty bit follows markDirty()").isTrue();

            project.markClean();
            flushFx();
            assertThat(snapshotOf(vm).dirty())
                    .as("the dirty bit follows markClean()").isFalse();
        } finally {
            disposeOnFx(vm);
        }
    }

    @Test
    void tracksFollowAddThenRemove() throws InterruptedException {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        ProjectVM vm = constructOnFx(project);
        try {
            Track drums = project.createAudioTrack("Drums");
            flushFx();
            List<Track> afterAdd = snapshotOf(vm).tracks();
            assertThat(afterAdd)
                    .as("the track list follows a core TRACKS add")
                    .containsExactly(drums);

            project.removeTrack(drums);
            flushFx();
            assertThat(snapshotOf(vm).tracks())
                    .as("the track list follows a core TRACKS remove")
                    .isEmpty();
        } finally {
            disposeOnFx(vm);
        }
    }

    @Test
    void tracksListIsUnmodifiable() throws InterruptedException {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        ProjectVM vm = constructOnFx(project);
        try {
            assertThatThrownBy(() -> vm.getTracks().add(null))
                    .as("the exposed track list is an unmodifiable view")
                    .isInstanceOf(UnsupportedOperationException.class);
        } finally {
            disposeOnFx(vm);
        }
    }

    @Test
    void disposeStopsUpdates() throws InterruptedException {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        ProjectVM vm = constructOnFx(project);
        boolean disposed = false;
        try {
            disposeOnFx(vm);
            disposed = true;

            project.setName("X");
            flushFx();
            assertThat(snapshotOf(vm).name())
                    .as("after dispose() the VM ignores further core signals")
                    .isEqualTo("Song");
        } finally {
            if (!disposed) {
                disposeOnFx(vm);
            }
        }
    }

    @Test
    void namePropertyIsReadOnly() throws InterruptedException {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        ProjectVM vm = constructOnFx(project);
        try {
            assertThat(vm.nameProperty()).isInstanceOf(ReadOnlyStringProperty.class);
        } finally {
            disposeOnFx(vm);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Construct on FX: the constructor seeds the backing {@code ObservableList}. */
    private static ProjectVM constructOnFx(DawProject project) throws InterruptedException {
        return callOnFx(() -> new ProjectVM(project, new FxDispatcher()));
    }

    private static void disposeOnFx(ProjectVM vm) throws InterruptedException {
        callOnFx(() -> {
            vm.dispose();
            return null;
        });
    }

    /** Reads name / dirty / a defensive copy of the tracks off the FX thread. */
    private static ProjectSnapshot snapshotOf(ProjectVM vm) throws InterruptedException {
        return callOnFx(() -> new ProjectSnapshot(
                vm.getName(), vm.isDirty(), List.copyOf(vm.getTracks())));
    }

    private record ProjectSnapshot(String name, boolean dirty, List<Track> tracks) {
    }

    // ── FX-thread helpers ──────────────────────────────────────────────────────

    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("FX queue must flush within %ds", TIMEOUT_SECONDS).isTrue();
    }

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
