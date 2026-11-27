package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.diagnostics.EngineFault;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureCodes;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.core.audio.health.LaneRole;
import com.benesquivelmusic.daw.core.audio.health.RenderThread;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window's own wiring of the engine fault to its Engine cell (story 338):
 * a window that was never FXML-loaded, given its engine and
 * notification bar as {@code initialize()} would, attaches its failure
 * funnel; the audio health drain its {@code attachAudioHealthDrain} builds
 * latches a render-drive halt, and the window's engine-health model — which
 * the strip's Engine cell shows — mirrors that fact; the window's disposal
 * lets it go.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class EngineFaultReachesTheEngineCellTest {

    @Test
    void theWindowsEngineHealthModelMirrorsTheDrainsFaultUntilTheWindowIsDisposed(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.detached(settings, System::nanoTime,
                InstantSource.system(), folder -> { })) {
            FxDispatcher dispatcher = new FxDispatcher();
            NotificationBar bar = onFx(() -> {
                NotificationBar made = new NotificationBar();
                made.setAnimated(false);
                made.setAutoDismissMillis(600_000);
                return made;
            });
            AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
            MainController window = onFx(() -> {
                MainController made = new MainController();
                made.setFxDispatcher(dispatcher);
                setField(made, "notificationBar", bar);
                setField(made, "audioEngine", engine);
                made.attachFailureFunnel(spine.funnel());
                return made;
            });
            try {
                MockAudioBackend backend = new MockAudioBackend();
                engine.setStreamingProvision(new StreamingProvision(backend.name(),
                        List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name())))));
                engine.startAudioOutput();
                assertThat(recordAHaltOnARenderThreadOf(engine)).as("fixture: the halt was recorded").isTrue();
                runOnFx(dispatcher::pulse);
                FailureSpineFixture.awaitOnFx("the drain latched the halt",
                        () -> window.audioHealthDrain().orElseThrow().engineFault().isPresent());

                Optional<EngineFault> latched = onFx(() -> window.audioHealthDrain().orElseThrow().engineFault());
                Optional<EngineFault> shown = onFx(() -> window.engineHealthStatus().engineFaultProperty().get());
                runOnFx(() -> {
                    try {
                        window.disposeWindowLifetime();
                    } catch (RuntimeException partOfAWindowThatWasNeverBuilt) {
                        // A reflected window lacks parts initialize() builds; the disposal ran every step anyway.
                    }
                });
                onFx(window::disposeWindowLifetime).toCompletableFuture().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
                Optional<EngineFault> afterDisposal = onFx(() ->
                        window.engineHealthStatus().engineFaultProperty().get());

                assertThat(shown).as("the model mirrors the drain's fact").isEqualTo(latched)
                        .hasValueSatisfying(fact -> assertThat(fact.category())
                                .isEqualTo(AudioFailureCategory.RENDER_HALTED));
                assertThat(afterDisposal).as("the window's disposal lets the drain's fact go").isEmpty();
            } finally {
                spine.funnel().disarmToasts();
                runOnFx(() -> {
                    bar.dismiss();
                    dispatcher.dispose();
                });
            }
        }
    }

    private static void setField(MainController window, String name, Object value) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(window, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Records a render-drive halt the way the render drive does: on a render thread carrying a lane of the record. */
    private static boolean recordAHaltOnARenderThreadOf(AudioEngine engine) throws InterruptedException {
        AudioHealthRecord health = engine.audioHealth();
        AtomicBoolean recorded = new AtomicBoolean();
        RenderThread drive = new RenderThread(health.claimLane(LaneRole.RENDER_DRIVE),
                () -> recorded.set(health.recordOnCurrentThread(AudioFailureCodes.RENDER_HALTED,
                        new IllegalStateException("the mixer lost its master bus"), null, 1)),
                "test-render-drive");
        drive.start();
        assertThat(drive.join(GUARD)).as("the render thread ended").isTrue();
        return recorded.get();
    }
}
