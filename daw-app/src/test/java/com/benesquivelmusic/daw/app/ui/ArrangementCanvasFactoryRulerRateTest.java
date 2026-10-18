package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import javafx.application.Platform;
import javafx.scene.Scene;
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

    private static ArrangementCanvasFactory.Result create(DawProject project) {
        StackPane content = new StackPane();
        VBox column = new VBox(content);
        new Scene(column, 800, 400);
        return ArrangementCanvasFactory.create(content, new StubHost(project), beat -> { });
    }

    /** A host that answers the project and inert defaults for everything else. */
    private record StubHost(DawProject project, UndoManager undoManager, SelectionModel selectionModel)
            implements ArrangementCanvasFactory.Host {

        StubHost(DawProject project) {
            this(project, new UndoManager(), new SelectionModel());
        }

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
