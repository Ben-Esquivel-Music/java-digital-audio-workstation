package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;
import org.junit.jupiter.api.AfterEach;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine-side facts story 338 adds for its watchdog that need no stream:
 * whether a take's capture is installed, the input-halt counter source, and
 * the input-halt fault's first line.
 */
class AudioEngineInputStallTest {

    private final AudioEngine engine = new AudioEngine(new AudioFormat(48000, 2, 16, 256));

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Test
    void aRecordingCaptureIsActiveFromItsInstallUntilAHaltRemovesIt() {
        assertThat(engine.isRecordingCaptureActive()).isFalse();
        engine.setRecordingCallback((input, frames) -> { });
        assertThat(engine.isRecordingCaptureActive()).isTrue();

        engine.haltRecordingCapture();

        assertThat(engine.isRecordingCaptureActive()).isFalse();
    }

    @Test
    void anEngineWithNoStreamHasNoCaptureInputAndAQuietInputHaltSource() {
        assertThat(engine.captureInputs()).isEmpty();
        assertThat(engine.capturedInputBlocksLost()).isZero();
        assertThat(engine.audioHealth().backendCounters())
                .as("the engine's own input-halt source reports nothing until a stall is declared")
                .isEqualTo(BackendThreadCounters.NONE);
    }

    @Test
    void theInputHaltFaultNamesTheInputInOneLineOfAtMost120Characters() {
        assertThat(InputStallLedger.stoppedMessage("Interface A"))
                .isEqualTo("input from “Interface A” stopped delivering audio");
        assertThat(InputStallLedger.stoppedMessage("  ")).isEqualTo("the audio input stopped delivering audio");
        assertThat(InputStallLedger.stoppedMessage(null)).isEqualTo("the audio input stopped delivering audio");
        assertThat(InputStallLedger.stoppedMessage(DeviceId.defaultFor("ASIO").name())).as("ruling E127")
                .isEqualTo("the audio input stopped delivering audio");

        String cut = InputStallLedger.stoppedMessage("A".repeat(200));
        assertThat(cut).hasSize(InputStallLedger.MAX_FAULT_LINE_LENGTH)
                .startsWith("input from “AAA").endsWith("A…” stopped delivering audio");
    }

    @Test
    void aRecordedStallReportsItsCountAndItsFault() {
        InputStallLedger ledger = new InputStallLedger();
        assertThat(ledger.counters()).isEqualTo(BackendThreadCounters.NONE);

        ledger.recordStall("Interface B");
        ledger.recordStall("Interface C");

        BackendThreadCounters counters = ledger.counters();
        assertThat(counters.inputHalts()).isEqualTo(2);
        assertThat(counters.lastInputFault()).get().extracting(Throwable::getMessage)
                .isEqualTo("input from “Interface C” stopped delivering audio");
        assertThat(counters.callbackFaults()).isZero();
        assertThat(counters.inputBlocksLost()).isZero();
        assertThat(counters.deviceCallbackPeriods()).isEmpty();
    }
}
