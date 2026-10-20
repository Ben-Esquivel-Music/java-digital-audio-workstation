package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 328 (book §5.6), review round 1 — the production wiring of
 * {@link TimelineRuler#setSampleRate(double)}: the ruler a project gets
 * through {@link ArrangementCanvasFactory#create} converts punch frames at
 * the PROJECT rate, not its 44.1 kHz default, and the rebind path re-applies
 * the next project's rate. {@code TimelineRulerPunchGestureTest} pins the
 * ruler's own draw math with a rate it sets itself; this pins who sets it.
 *
 * <p>PR #984 review — likewise the production wiring of
 * {@link TimelineRuler#setOnTransportEdited}: after a project switch, the
 * factory's ruler marks the project it was rebound to dirty, not the one it
 * was built with. {@code TimelineRulerGesturesReportEditsOnReleaseTest} pins
 * when the ruler runs the callback; this pins what the factory wires.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ArrangementCanvasFactoryRulerRateTest {

    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void theFactoryWiresTheRulerToTheProjectsRateSoPunchHandlesDrawAtProjectFrames() throws Exception {
        DawProject project = new DawProject("96k", AudioFormat.STUDIO_QUALITY);
        project.getTransport().setTempo(120.0);
        assertThat(project.getFormat().sampleRate()).as("premise: the project default is 96 kHz").isEqualTo(96_000.0);
        ArrangementCanvasFactory.Result result = computeOnFx(() -> create(project));

        TimelineRuler ruler = result.ruler();
        assertThat(ruler.getSampleRate()).as("the factory applies the project rate").isEqualTo(96_000.0);

        // Beat 2 at 120 BPM is 1 s = 96 000 project frames; drawn at 2 beats
        // × pixelsPerBeat, not at the 44.1 kHz misdraw (~2.18× further right).
        project.getTransport().setPunchRegion(PunchRegion.enabled(96_000L, 288_000L));
        double expectedX = computeOnFx(() -> {
            ruler.setAutoScroll(false);
            ruler.getParent().applyCss();
            ruler.getParent().layout();
            ruler.redraw();
            return 2.0 * ruler.getPixelsPerBeat();
        });
        assertThat(computeOnFx(ruler::lastDrawnPunchInX)).isCloseTo(expectedX, within(0.5));

        // A second project at 48 kHz: the ruler it gets follows that rate.
        DawProject other = new DawProject("48k", new AudioFormat(48_000.0, 2, 24, 256));
        ArrangementCanvasFactory.Result rebound = computeOnFx(() -> create(other));
        assertThat(rebound.ruler().getSampleRate()).isEqualTo(48_000.0);
    }

    @Test
    void theProjectSwitchPathReappliesTheRulersRateFromTheNewProject() throws Exception {
        // MainController.rebuildViewModels() is not constructible headlessly
        // (FXML-loaded); pinned by source like EngineBinderConformanceTest
        // pins the engine binder's wiring there.
        Path file = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");
        String code = SourceScanSupport.stripStringLiterals(
                SourceScanSupport.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
        int start = code.indexOf("private void rebuildViewModels()");
        assertThat(start).as("MainController.rebuildViewModels exists").isGreaterThanOrEqualTo(0);
        int next = code.indexOf("\n    private ", start + 1);
        String body = code.substring(start, next < 0 ? code.length() : next);
        assertThat(body)
                .as("rebuildViewModels() re-applies the ruler's rate from the project being bound")
                .contains("timelineRuler.setSampleRate(project.getFormat().sampleRate())");
    }

    @Test
    void theFactoryRulerMarksTheLiveProjectDirtyAfterAProjectSwitch() throws Exception {
        DawProject opened = new DawProject("opened", new AudioFormat(48_000.0, 2, 24, 256));
        DawProject next = new DawProject("next", AudioFormat.STUDIO_QUALITY);
        next.getTransport().setLoopWindow(true, 2.0, 6.0);
        AtomicReference<DawProject> live = new AtomicReference<>(opened);
        ArrangementCanvasFactory.Result result = computeOnFx(() -> create(new StubHost(live)));
        TimelineRuler ruler = result.ruler();

        // The project switch as MainController.rebuildViewModels() performs it on
        // the ruler: the host answers the new project, the ruler is rebound to it.
        live.set(next);
        computeOnFx(() -> {
            ruler.setAutoScroll(false);
            ruler.rebind(next.getTransport());
            ruler.setSampleRate(next.getFormat().sampleRate());
            ruler.getParent().applyCss();
            ruler.getParent().layout();
            opened.markClean();
            next.markClean();
            return null;
        });
        Canvas canvas = computeOnFx(() -> (Canvas) ruler.getChildrenUnmodifiable().getFirst());

        computeOnFx(() -> {
            // 40 px per beat, no scroll: the loop's end handle (beat 6) to beat 8.
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, 240.0));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_DRAGGED, 320.0));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, 320.0));
            return null;
        });

        assertThat(next.getTransport().getLoopWindow())
                .as("guard: the gesture moved the loop of the project the ruler was rebound to")
                .isEqualTo(new Transport.LoopWindow(true, 2.0, 8.0));
        assertThat(next.isDirty()).as("the project the ruler is rebound to is marked").isTrue();
        assertThat(opened.isDirty()).as("not the project the ruler was built with").isFalse();
    }

    private static ArrangementCanvasFactory.Result create(DawProject project) {
        return create(new StubHost(project));
    }

    private static ArrangementCanvasFactory.Result create(ArrangementCanvasFactory.Host host) {
        StackPane content = new StackPane();
        VBox column = new VBox(content);
        new Scene(column, 800, 400);
        return ArrangementCanvasFactory.create(content, host, beat -> { });
    }

    /** An upper-half (loop) primary-button event without modifiers; y = 8 on the 32 px ruler. */
    private static MouseEvent mouse(EventType<MouseEvent> type, double x) {
        double y = 8.0;
        boolean primaryDown = type != MouseEvent.MOUSE_RELEASED;
        return new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false,
                primaryDown, false, false,
                false, false, false, null);
    }

    /**
     * A host that answers the live project — swappable, as {@code MainController}'s
     * is on Open / New — and inert defaults for everything else.
     */
    private record StubHost(AtomicReference<DawProject> live, UndoManager undoManager, SelectionModel selectionModel)
            implements ArrangementCanvasFactory.Host {

        StubHost(DawProject project) {
            this(new AtomicReference<>(project));
        }

        StubHost(AtomicReference<DawProject> live) {
            this(live, new UndoManager(), new SelectionModel());
        }

        @Override public DawProject project() { return live.get(); }
        @Override public EditTool activeEditTool() { return EditTool.POINTER; }
        @Override public boolean isSnapEnabled() { return false; }
        @Override public GridResolution gridResolution() { return GridResolution.QUARTER; }
        @Override public void refreshCanvas() { }
        @Override public void seekToPosition(double beat) { }
        @Override public void updateStatusBar(String text) { }
        @Override public RippleMode rippleMode() { return RippleMode.OFF; }
        @Override public void showNotification(NotificationLevel level, String message) { }
        @Override public void onTimeStretchClip() { }
        @Override public void onPitchShiftClip() { }
    }

    private static <T> T computeOnFx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return action.call();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the FX action returned in time").isTrue();
        if (thrown.get() instanceof Exception e) throw e;
        if (thrown.get() instanceof Error e) throw e;
        return result.get();
    }
}
