package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.sdk.audio.BackendDropoutCounters;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import javafx.scene.Scene;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The xrun count and the xrun log end to end (story 338, the story's xrun
 * feed test, rulings E17, E20, E22 and E23): a real engine controller over a
 * real engine whose render drive sinks into a spy backend, its xrun journal,
 * the window's engine-health feed registered on an owned dispatcher, the
 * status strip's Xruns cell and the xrun log dialog. A forced-late block
 * fills the log and the tooltip's late count and leaves the number alone; a
 * gap raises the number and fills the log; Clear empties both. The health
 * clock stands still, so the watchdog decides nothing.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class XrunCountAndLogSurfacesTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 128);

    private final AudioEngine engine = new AudioEngine(FORMAT);
    private final DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null,
            new RecordingNotifications());
    private final SpyStreamingBackend spy = new SpyStreamingBackend("SpyXruns");
    private final FxDispatcher dispatcher = new FxDispatcher();

    XrunCountAndLogSurfacesTest() {
        controller.setEngineHealthClock(() -> 0L);
        engine.setStreamingProvision(new StreamingProvision("SpyXruns",
                List.of(new BackendStreamRung(spy, new DeviceId("SpyXruns", "Interface A")))));
    }

    @AfterEach
    void tearDown() {
        engine.stopAudioOutput();
        engine.stop();
        controller.shutdown();
    }

    @Test
    void aLateBlockIsLoggedButNotCountedAGapIsBothAndClearEmptiesBoth() throws Exception {
        AtomicInteger slowBlocksLeft = new AtomicInteger();
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            if (slowBlocksLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(7));
            }
        });
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = EngineHealthFeed.forWindow(model, controller, () -> null, () -> null,
                _ -> Optional.empty());
        Runnable removal = dispatcher.addPulseParticipant(feed::pulse);
        SessionStatusStrip strip = onFx(() -> {
            SessionStatusStrip made = new SessionStatusStrip(new ProjectOperationProgress(new FxDispatcher()),
                    MotionManager.getDefault(), model);
            VBox root = new VBox(made);
            new Scene(root, 1400, 120);
            root.applyCss();
            root.layout();
            return made;
        });
        XrunLogDialog dialog = onFx(() -> new XrunLogDialog(null, model, feed::clearXrunLog));
        try {
            startCounting();

            slowBlocksLeft.set(3);
            await(() -> controller.getXrunDetector().getLateCount()
                    + controller.getXrunDetector().getDroppedCount() >= 3, "the slow blocks were timed");
            tickAfterBlocks();
            runOnFx(dispatcher::pulse);
            String lateText = onFx(() -> strip.xrunCell().getText());
            String lateTooltip = onFx(() -> strip.xrunCell().getTooltip().getText());
            List<String> lateLog = onFx(() -> List.copyOf(dialog.entryList().getItems()));

            spy.dropouts = new BackendDropoutCounters(5, 0, 0, 0, 0, true);
            tickAfterBlocks();
            tickAfterBlocks();
            runOnFx(dispatcher::pulse);
            String gapText = onFx(() -> strip.xrunCell().getText());
            List<String> gapLog = onFx(() -> List.copyOf(dialog.entryList().getItems()));

            runOnFx(() -> dialog.clearButton().fire());
            runOnFx(dispatcher::pulse);
            String clearedText = onFx(() -> strip.xrunCell().getText());
            List<String> clearedLog = onFx(() -> List.copyOf(dialog.entryList().getItems()));

            assertThat(lateText).as("a late block never raises the number (ruling E17)").isEqualTo("Xruns 0");
            assertThat(lateTooltip).as("the tooltip counts the late blocks (ruling E23)")
                    .matches("0 dropouts since \\d\\d:\\d\\d:\\d\\d \\(0 in the last 30 seconds\\); [1-9]\\d* late"
                            + " blocks?\\. Click for the xrun log\\.( This audio driver does not report underruns;"
                            + " late blocks are listed in the log\\.)?");
            assertThat(lateLog).as("and the log lists them (ruling E20)").isNotEmpty()
                    .allMatch(line -> line.matches("\\d\\d:\\d\\d:\\d\\d\\.\\d{3}  Late  Block took \\d+\\.\\d ms of a"
                            + " 2\\.7 ms period( ×\\d+)?"));
            assertThat(gapText).as("the starved periods raise the number").isEqualTo("Xruns 5");
            assertThat(gapLog).as("and are logged as one entry with ×n")
                    .anyMatch(line -> line.endsWith("  Gap  Device played silence (output buffer empty) ×5"));
            assertThat(clearedText).as("Clear restarts the count (ruling E22)").isEqualTo("Xruns 0");
            assertThat(clearedLog).as("and empties the log").isEmpty();
        } finally {
            removal.run();
        }
    }

    /** Streams and ticks until the feed counts: the epoch is seen, then the drive's first advance. */
    private void startCounting() throws InterruptedException {
        engine.startAudioOutput();
        controller.tickEngineHealthMonitor();
        tickAfterBlocks();
        tickAfterBlocks();
    }

    private void tickAfterBlocks() throws InterruptedException {
        assertThat(spy.awaitSunkBlocks(2, 10, TimeUnit.SECONDS)).as("the render drive keeps sinking").isTrue();
        controller.tickEngineHealthMonitor();
    }
}
