package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.ClockStatusIndicator.ClockReading;
import com.benesquivelmusic.daw.app.ui.ClockStatusIndicator.State;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.OpenedStream;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.sdk.audio.ClockKind;
import com.benesquivelmusic.daw.sdk.audio.ClockLockStatus;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clock badge between the start of a device loss's declaration and its
 * DEVICE_LOST (story 338, review D finding 11): the engine controller
 * declares the loss holding its monitor, the stream is already closed and
 * DEVICE_LOST not yet published, and the badge's pulse — which reads the
 * engine and the controller through the window's {@code engineClock}, never
 * waiting on the controller's monitor — shows nothing, never the closed
 * treatment of an ordinary Stop.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ClockBadgeWhileALossIsDeclaredTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 128);
    private static final DeviceId DEVICE = new DeviceId("SpyLoss", "Interface A");
    private static final ClockSource INTERNAL = new ClockSource(0, "Internal", true, new ClockKind.Internal());

    private final AudioEngine engine = new AudioEngine(FORMAT);
    private final DefaultAudioEngineController controller =
            new DefaultAudioEngineController(engine, null, new RecordingNotifications());
    private final SpyStreamingBackend spy = new SpyStreamingBackend(DEVICE.backend());

    @AfterEach
    void tearDown() {
        spy.threadCountersHook = null;
        engine.stopAudioOutput();
        engine.stop();
        controller.shutdown();
    }

    @Test
    void theBadgeShowsNothingFromTheStartOfALossDeclarationNeverAClosedStream() throws Exception {
        engine.setStreamingProvision(new StreamingProvision(DEVICE.backend(),
                List.of(new BackendStreamRung(spy, DEVICE))));
        engine.startAudioOutput();
        ClockStatusIndicator.StreamClock window = ClockStatusIndicator.engineClock(engine, controller);
        ClockStatusIndicator badge = onFx(() -> new ClockStatusIndicator(NotificationManager.noop(),
                new NoTake(), new FixedLabel(window), null, Runnable::run));
        badge.streamOpened();
        runOnFx(badge::pulse);
        assertThat(onFx(() -> badge.stateProperty().get())).as("premise: the internal source on show")
                .isEqualTo(State.QUIET);

        AtomicBoolean looked = new AtomicBoolean();
        AtomicReference<Object> whileDeclared = new AtomicReference<>();
        spy.threadCountersHook = () -> {
            if (engine.isStreamOpen() || controller.engineState() == EngineState.DEVICE_LOST
                    || !looked.compareAndSet(false, true)) {
                return;
            }
            try {
                whileDeclared.set(onFx(() -> {
                    badge.pulse();
                    return badge.stateProperty().get();
                }));
            } catch (Exception | AssertionError failure) {
                whileDeclared.set(failure);
            }
        };
        spy.simulateDeviceRemoved(DEVICE);
        await(looked::get, "the badge looked while the loss was being declared");
        await(() -> controller.engineState() == EngineState.DEVICE_LOST, "the loss was declared");

        assertThat(whileDeclared.get()).as("the stream closed, DEVICE_LOST not yet published: hidden, never CLOSED")
                .isEqualTo(State.HIDDEN);
    }

    /**
     * The badge's three reads take no monitor: the controller's monitor is
     * held across driver calls during a loss (review E finding 9).
     */
    @Test
    void theBadgesReadsOfTheControllerAreNotSynchronized() throws Exception {
        for (String read : List.of("clockLockStatus", "isDeviceLost", "engineState")) {
            assertThat(Modifier.isSynchronized(DefaultAudioEngineController.class.getMethod(read).getModifiers()))
                    .as("DefaultAudioEngineController.%s() is not synchronized", read).isFalse();
        }
    }

    /** The window's reads of the engine, with a label read that needs no driver. */
    private record FixedLabel(ClockStatusIndicator.StreamClock window) implements ClockStatusIndicator.StreamClock {
        @Override
        public boolean streamOpen() {
            return window.streamOpen();
        }

        @Override
        public boolean deviceLost() {
            return window.deviceLost();
        }

        @Override
        public Optional<ClockLockStatus> lockStatus() {
            return window.lockStatus();
        }

        @Override
        public Optional<OpenedStream> lastOpened() {
            return window.lastOpened();
        }

        @Override
        public Optional<ClockReading> read() {
            return Optional.of(new ClockReading(DEVICE, List.of(INTERNAL), 48_000));
        }

        @Override
        public Optional<ClockReading> read(DeviceId device) {
            return read();
        }
    }

    private static final class NoTake implements TakeClockMarks {
        @Override
        public TakeClockMarks.TakeRecording takeRecording() {
            return TakeClockMarks.TakeRecording.NONE;
        }

        @Override
        public boolean lockChanged(boolean locked, long evidenceNanos) {
            return false;
        }

        @Override
        public boolean takeStartsUnlocked() {
            return false;
        }
    }
}
