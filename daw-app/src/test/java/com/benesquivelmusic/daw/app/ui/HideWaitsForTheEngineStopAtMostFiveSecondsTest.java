package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;

import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 333, the studio engineer's ruling 2 OQ-3 (and S9): DISPOSE hands the
 * audio engine's stop to its own thread, and HIDE waits for that hand-off at
 * most five seconds while the progress surface keeps painting "Stopping
 * audio…" — no note, no button, Escape and the close box doing nothing; a
 * hand-off that ends first hides at once, and the bound that fires later
 * does nothing. The hand-off's thread is a daemon named
 * {@code daw-engine-shutdown}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class HideWaitsForTheEngineStopAtMostFiveSecondsTest {

    @TempDir
    Path workspace;

    private ExitFixture fixture;

    @BeforeEach
    void songA() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void aHandOffStillStoppingAfterFiveSecondsHidesTheWindowAtTheBound() throws Exception {
        fixture.handOff = new CompletableFuture<>();

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(surface::stepText)).isEqualTo("Stopping audio…");
        assertThat(onFx(surface::noteText)).isEmpty();
        assertThat(onFx(() -> surface.keepButton().isVisible() || surface.offerButton().isVisible()))
                .as("no buttons").isFalse();
        runOnFx(() -> {
            Node target = surface.getDialogPane();
            target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
        });
        assertThat(onFx(surface::isOpen)).as("Escape does nothing").isTrue();
        assertThat(fixture.reached(ExitCoordinator.ExitStep.HIDE)).isFalse();
        assertThat(onFx(() -> fixture.stage().isShowing())).isTrue();

        fixture.fire(ExitCoordinator.ENGINE_STOP_BOUND);

        assertThat(fixture.stepNames()).endsWith("DISPOSE DONE", "HIDE DONE");
        assertThat(onFx(() -> fixture.stage().isShowing())).isFalse();
        assertThat(fixture.terminalExits).hasValue(1);
        assertThat(onFx(() -> fixture.coordinator().protocolCompleted())).isTrue();
    }

    @Test
    void aHandOffThatEndsFirstHidesAtOnceAndTheLaterBoundDoesNothing() throws Exception {
        CompletableFuture<Void> handOff = new CompletableFuture<>();
        fixture.handOff = handOff;
        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.DISPOSE);

        handOff.complete(null);
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        fixture.fire(ExitCoordinator.ENGINE_STOP_BOUND);
        fixture.closeBox();
        fixture.save.settle();

        assertThat(fixture.stepNames()).filteredOn(name -> name.startsWith("HIDE")).hasSize(1);
        assertThat(fixture.terminalExits).as("once").hasValue(1);
        assertThat(fixture.disposals).as("a close request after HIDE runs nothing").hasValue(1);
    }

    @Test
    void aSurfaceShowingAtDisposeDropsItsNoteAndButtonsForStoppingAudio() throws Exception {
        fixture.handOff = new CompletableFuture<>();
        SaveFlowFixture.Publishes.Hold closing = fixture.save.publishes.holdNext(SaveFlowFixture::isCheckpoint);
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Discard and close";
        fixture.closeBox();
        closing.awaitHeld();
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(surface::noteText)).isNotEmpty();

        closing.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.DISPOSE);

        assertThat(onFx(surface::stepText)).isEqualTo("Stopping audio…");
        assertThat(onFx(surface::noteText)).as("the recover texts are false after a clean seal").isEmpty();
        assertThat(onFx(() -> surface.offerButton().isVisible() || surface.keepButton().isVisible())).isFalse();
        fixture.fire(ExitCoordinator.ENGINE_STOP_BOUND);
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
    }

    @Test
    void theHandOffRunsOnADaemonThreadNamedForTheEngineShutdown() throws Exception {
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        ExitCoordinator.onTheEngineShutdownThread(() -> ranOn.set(Thread.currentThread()))
                .toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(ranOn.get().getName()).isEqualTo("daw-engine-shutdown");
        assertThat(ranOn.get().isDaemon()).as("ruling 2 OQ-3: a daemon").isTrue();
        assertThat(ranOn.get().isVirtual()).isFalse();
        assertThatThrownBy(() -> ExitCoordinator.onTheEngineShutdownThread(() -> {
            throw new IllegalStateException("the driver threw");
        }).toCompletableFuture().join()).isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("the driver threw");
    }
}
