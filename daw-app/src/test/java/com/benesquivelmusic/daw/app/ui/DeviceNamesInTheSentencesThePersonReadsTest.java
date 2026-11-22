package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.recording.EarlySeal;
import com.benesquivelmusic.daw.core.recording.FinalManifestFailure;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ruling E111: a device with no name to show — the default placeholder, or
 * the transport's stand-in for a device nobody named — is "The audio device"
 * at the start of a sentence and "the audio device" inside one, in every
 * sentence the record coordinator words; a named device keeps its name.
 */
class DeviceNamesInTheSentencesThePersonReadsTest {

    private static final DeviceId PLACEHOLDER = DeviceId.defaultFor("Java Sound");
    private static final DeviceId NAMED = new DeviceId("ASIO", "Interface A");

    @Test
    void onlyARealNameIsShown() {
        assertThat(DeviceNames.shown(PLACEHOLDER)).isEmpty();
        assertThat(DeviceNames.shown(TransportController.UNKNOWN_DEVICE)).isEmpty();
        assertThat(DeviceNames.shown((DeviceId) null)).isEmpty();
        assertThat(DeviceNames.shown(NAMED)).contains("Interface A");
        assertThat(DeviceNames.atSentenceStart(PLACEHOLDER)).isEqualTo("The audio device");
        assertThat(DeviceNames.insideSentence(PLACEHOLDER)).isEqualTo("the audio device");
        assertThat(DeviceNames.atSentenceStart(NAMED)).isEqualTo("Interface A");
    }

    @Test
    void theRescuedTakesLeadsSayTheAudioDeviceAtEveryCause() {
        assertThat(RecordCoordinator.rescuedTakeMessage(PLACEHOLDER, EngineStateChange.LossCause.STALLED, 2, 1))
                .isEqualTo("The audio device stopped responding during recording. The take was saved up to the"
                        + " dropout: 2 clips on 1 track.");
        assertThat(RecordCoordinator.rescuedTakeMessage(PLACEHOLDER, EngineStateChange.LossCause.REMOVED, 0, 1))
                .isEqualTo("The audio device was disconnected during recording; nothing had reached disk yet.");
        assertThat(RecordCoordinator.rescuedTakeMessage(PLACEHOLDER,
                EngineStateChange.LossCause.REOPEN_AFTER_RESET_FAILED, 1, 1))
                .isEqualTo("The audio device could not be reopened after a driver reset during recording. The take"
                        + " was saved up to the dropout: 1 clip on 1 track.");
        assertThat(RecordCoordinator.rescuedTakeMessage(NAMED, EngineStateChange.LossCause.REOPEN_AFTER_RESET_FAILED,
                1, 1)).startsWith("Interface A could not be reopened after a driver reset during recording.");
        assertThat(RecordCoordinator.rescuedTakeManifestNotWrittenMessage(PLACEHOLDER,
                EngineStateChange.LossCause.STALLED, new FinalManifestFailure(new IOException("disk full"),
                        TakeManifest.SealedBy.DEVICE_LOSS), 1, 1))
                .startsWith("The audio device stopped responding during recording. The audio was saved");
        assertThat(RecordCoordinator.rescuedTakeSealedEarlyMessage(PLACEHOLDER,
                EngineStateChange.LossCause.REOPEN_AFTER_RESET_FAILED,
                new EarlySeal.DiskExhausted(1L << 30, false, true), 1, Path.of("take")))
                .startsWith("The audio device could not be reopened after a driver reset during recording. Saving");
    }

    @Test
    void theStatusTheReturnAndTheRefusalsSayTheAudioDevice() {
        assertThat(RecordCoordinator.deviceLostStatus(PLACEHOLDER, false)).isEqualTo("Audio device lost; saving the"
                + " take");
        assertThat(RecordCoordinator.deviceLostStatus(NAMED, true)).isEqualTo("Audio device lost — Interface A; take"
                + " preserved");
        assertThat(RecordCoordinator.reconnectedDuringTakeMessage(PLACEHOLDER))
                .isEqualTo("The audio device reconnected. Tracks are still armed; press Record to continue.");
        assertThat(RecordCoordinator.lostDeviceText(PLACEHOLDER)).isEqualTo("the audio device");
        assertThat(RecordCoordinator.lostDeviceText(NAMED)).isEqualTo("audio device Interface A");
    }

    /**
     * Ruling E128: an audio failure notice names the open device when it has
     * a name to show, else the backend — never the placeholder — else nothing.
     */
    @Test
    void anAudioFailureNoticeNamesTheBackendForThePlaceholder() {
        assertThat(DeviceNames.audioNoticeDevice(Optional.of(PLACEHOLDER), Optional.of("ASIO"), Optional.of("PortAudio")))
                .isEqualTo("ASIO");
        assertThat(DeviceNames.audioNoticeDevice(Optional.of(NAMED), Optional.of("ASIO"), Optional.empty()))
                .isEqualTo("Interface A");
        assertThat(DeviceNames.audioNoticeDevice(Optional.empty(), Optional.empty(), Optional.of("PortAudio")))
                .as("no stream open: the provision's backend").isEqualTo("PortAudio");
        assertThat(DeviceNames.audioNoticeDevice(Optional.of(PLACEHOLDER), Optional.empty(), Optional.empty()))
                .as("no name at all").isEmpty();
    }

    /** Ruling E125: the removal lead says "was disconnected", named and unnamed, in full. */
    @Test
    void theRemovalLeadSaysWasDisconnected() {
        assertThat(RecordCoordinator.rescuedTakeMessage(PLACEHOLDER, EngineStateChange.LossCause.REMOVED, 2, 1))
                .isEqualTo("The audio device was disconnected during recording. The take was saved up to the"
                        + " dropout: 2 clips on 1 track.");
        assertThat(RecordCoordinator.rescuedTakeMessage(NAMED, EngineStateChange.LossCause.NONE, 1, 1))
                .isEqualTo("Interface A was disconnected during recording. The take was saved up to the dropout:"
                        + " 1 clip on 1 track.");
    }

    /** Ruling E130: the four combinations; never "the audio device" for both devices. */
    @Test
    void theLossEndingOnAnotherDeviceNamesEachDeviceOrSaysWhichOneItIs() {
        DeviceId current = new DeviceId("ASIO", "Interface B");
        String tail = " did not return. Tracks are still armed; press Record to continue.";
        assertThat(RecordCoordinator.lossEndedOnAnotherDeviceMessage(current, NAMED))
                .isEqualTo("Audio is now on Interface B; Interface A" + tail);
        assertThat(RecordCoordinator.lossEndedOnAnotherDeviceMessage(PLACEHOLDER, NAMED))
                .isEqualTo("Audio is now on another audio device; Interface A" + tail);
        assertThat(RecordCoordinator.lossEndedOnAnotherDeviceMessage(current, PLACEHOLDER))
                .isEqualTo("Audio is now on Interface B; the lost device" + tail);
        assertThat(RecordCoordinator.lossEndedOnAnotherDeviceMessage(PLACEHOLDER, PLACEHOLDER))
                .isEqualTo("Audio is now on another audio device; the lost device" + tail);
    }

    /**
     * Ruling E126: Record or Play pressed while the device is lost, and
     * another device arriving, say what became of the lost device in the
     * words of its cause, named and unnamed; {@code NONE} reads as removed.
     */
    @ParameterizedTest
    @EnumSource(EngineStateChange.LossCause.class)
    void theStillLostSentencesFollowTheCause(EngineStateChange.LossCause cause) {
        String named = switch (cause) {
            case STALLED -> "Interface A stopped responding and has not been reopened";
            case REOPEN_AFTER_RESET_FAILED -> "Interface A asked to be reset and has not been reopened";
            case REMOVED, NONE -> "Interface A is still disconnected";
        };
        String unnamed = "the audio device" + named.substring("Interface A".length());
        String ways = cause == EngineStateChange.LossCause.STALLED
                || cause == EngineStateChange.LossCause.REOPEN_AFTER_RESET_FAILED
                ? "; choose Reconnect, press Stop, or choose another device in Audio Settings"
                : "; reconnect it, press Stop, or choose another device in Audio Settings";
        assertThat(RecordCoordinator.unavailableWhileDeviceLostMessage("Record", Optional.of(NAMED), cause))
                .isEqualTo("Record is unavailable — " + named + ways);
        assertThat(RecordCoordinator.unavailableWhileDeviceLostMessage("Playback", Optional.of(PLACEHOLDER), cause))
                .isEqualTo("Playback is unavailable — " + unnamed + ways);
        assertThat(RecordCoordinator.unavailableWhileDeviceLostMessage("Record", Optional.empty(), cause))
                .isEqualTo("Record is unavailable — " + unnamed + ways);

        String arrived = " — Interface C arrived, but it is not the lost device; playback stays stopped";
        DeviceId interfaceC = new DeviceId("ASIO", "Interface C");
        assertThat(DefaultAudioEngineController.anotherDeviceArrivedMessage(NAMED, cause, interfaceC))
                .isEqualTo(named + arrived);
        assertThat(DefaultAudioEngineController.anotherDeviceArrivedMessage(PLACEHOLDER, cause, interfaceC))
                .isEqualTo("The audio device" + named.substring("Interface A".length()) + arrived);
        assertThat(DefaultAudioEngineController.anotherDeviceArrivedMessage(NAMED, cause, PLACEHOLDER))
                .isEqualTo(named + " — another audio device arrived, but it is not the lost device; playback"
                        + " stays stopped");
    }
}
