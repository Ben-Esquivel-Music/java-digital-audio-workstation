package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.sdk.audio.ClockKind;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A clock source chosen in Audio Settings, through the real engine
 * controller (story 338): the clock-source-selected callback runs after the
 * backend's call, also when the backend refuses, outside the controller's
 * monitor (review D finding 1); and a switch on the open stream's device
 * re-arms the heartbeat watchdog's start-up allowance, a switch on another
 * device does not (ruling E107).
 */
@Tag("slow")
class ClockSourceSelectionTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 128);
    private static final String SPY = "SpyClock";
    private static final DeviceId OPEN_DEVICE = new DeviceId(SPY, "Interface A");

    private final AudioEngine engine = new AudioEngine(FORMAT);
    private final RecordingNotifications notices = new RecordingNotifications();
    private final DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices);
    private final SpyStreamingBackend spy = new SpyStreamingBackend(SPY);
    private final AtomicLong clock = new AtomicLong();

    @AfterEach
    void tearDown() {
        spy.blockAwait = false;
        engine.stopAudioOutput();
        engine.stop();
        controller.shutdown();
    }

    @Test
    void aSelectionRunsTheCallbackAfterTheBackendOutsideTheControllersMonitor() {
        MockAudioBackend mock = new MockAudioBackend();
        mock.setClockSources(List.of(new ClockSource(0, "Internal", true, new ClockKind.Internal()),
                new ClockSource(1, "Word Clock", false, new ClockKind.WordClock())));
        engine.setStreamingProvision(new StreamingProvision(mock.name(),
                List.of(new BackendStreamRung(mock, new DeviceId(mock.name(), "Interface A")))));
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        controller.setClockSourceSelectedCallback(() -> seen.add("selections=" + mock.recordedClockSourceSelections()
                + " monitorHeld=" + Thread.holdsLock(controller)));

        controller.selectClockSource(mock.name(), "Interface A", 1);

        assertThat(seen).as("once, after the driver took the source, with the monitor released")
                .containsExactly("selections=[1] monitorHeld=false");
    }

    @Test
    void aSelectionTheBackendRefusesStillRunsTheCallback() {
        engine.setStreamingProvision(new StreamingProvision(SPY, List.of(new BackendStreamRung(spy, OPEN_DEVICE))));
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        controller.setClockSourceSelectedCallback(() -> seen.add("ran"));

        assertThatThrownBy(() -> controller.selectClockSource(SPY, OPEN_DEVICE.name(), 1))
                .as("premise: this backend refuses clock-source selection")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(seen).as("the badge reads the source again whatever the driver answered").containsExactly("ran");
    }

    /** Ruling E107: never "stopped responding" within 5 s of a switch on the open stream's device. */
    @Test
    void aSwitchOnTheOpenStreamsDeviceGetsTheStartUpAllowance() throws Exception {
        streamUntilTheRenderPumpIsWedged();

        selectQuietly(OPEN_DEVICE.name());
        boolean lostWithinTheAllowance = ticksUntilLost(45);
        boolean lostAfterIt = ticksUntilLost(15);

        assertThat(lostWithinTheAllowance).as("no verdict within 4.5 s of the switch").isFalse();
        assertThat(lostAfterIt).as("the verdict once max(5 s, window) passed with no advance").isTrue();
    }

    /**
     * Ruling E107 on the automatic device: the open stream's device is the
     * placeholder, and Settings passes the output row's value, which is
     * blank for an automatic output — the switch is on the open stream's
     * device and gets the allowance.
     */
    @Test
    void aSwitchFromSettingsOnTheAutomaticDeviceGetsTheStartUpAllowance() throws Exception {
        streamUntilTheRenderPumpIsWedged(DeviceId.defaultFor(SPY));

        selectQuietly("");
        boolean lostWithinTheAllowance = ticksUntilLost(45);
        boolean lostAfterIt = ticksUntilLost(15);

        assertThat(lostWithinTheAllowance).as("no verdict within 4.5 s of the switch").isFalse();
        assertThat(lostAfterIt).as("the verdict once max(5 s, window) passed with no advance").isTrue();
    }

    /** Ruling E107: never an allowance granted for anything but that switch. */
    @Test
    void aSwitchOnAnotherDeviceGetsNoAllowance() throws Exception {
        streamUntilTheRenderPumpIsWedged();

        selectQuietly("Interface B");

        assertThat(ticksUntilLost(30)).as("the normal window: a verdict within 3 s").isTrue();
    }

    private void streamUntilTheRenderPumpIsWedged() throws InterruptedException {
        streamUntilTheRenderPumpIsWedged(OPEN_DEVICE);
    }

    private void streamUntilTheRenderPumpIsWedged(DeviceId openOn) throws InterruptedException {
        controller.setEngineHealthClock(clock::get);
        engine.setStreamingProvision(new StreamingProvision(SPY, List.of(new BackendStreamRung(spy, openOn))));
        engine.startAudioOutput();
        controller.tickEngineHealthMonitor();
        long beats = engine.audioHealth().renderDriveBeats();
        await(() -> engine.audioHealth().renderDriveBeats() >= beats + 2, "the render drive beats");
        tick();
        spy.blockAwait = true;
        await(() -> spy.blockedAwaitEntries.get() >= 1, "the render pump is wedged");
        tick();
    }

    /** The spy refuses the selection itself; the switch was sent all the same. */
    private void selectQuietly(String deviceName) {
        try {
            controller.selectClockSource(SPY, deviceName, 1);
        } catch (UnsupportedOperationException refused) {
            assertThat(refused).as("premise: the spy refuses clock-source selection").isNotNull();
        }
    }

    private boolean ticksUntilLost(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick();
            if (controller.engineState() == EngineState.DEVICE_LOST) {
                return true;
            }
        }
        return false;
    }

    private void tick() {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(DefaultAudioEngineController.ENGINE_HEALTH_TICK_MILLIS));
        controller.tickEngineHealthMonitor();
    }
}
