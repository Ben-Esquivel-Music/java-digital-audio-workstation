package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 316 re-review — the Settings dialog builds its live engine
 * reconfigure from the REQUESTED endpoint, not the PROVISIONED one.
 *
 * <p>{@link AudioEngineController#getProvisionedBackendName()} answers "the
 * open stream's backend while one is open, else the installed provision's
 * first rung". That is the right authority for capability work — enumerate
 * devices, query buffer sizes, set a sample rate on a driver — and the wrong
 * authority for "which endpoint should the engine be reconfigured to open",
 * for two independent reasons that these tests pin:</p>
 *
 * <ul>
 *   <li>it flips with TRANSPORT STATE. After the ladder falls back it names
 *       the fallback while that stream is open and the requested head rung
 *       once it is stopped, so a buffer-size-only Apply used to target a
 *       different backend depending on whether the transport happened to be
 *       running — silently pinning the engine to a fallback, or promoting a
 *       fallback back to the request;</li>
 *   <li>it ignores the user's own pending endpoint edits, which the very same
 *       Apply persists a moment later — so the live stream and the saved
 *       settings could end up naming different devices.</li>
 * </ul>
 *
 * <p>The correction is NOT "build the request from pending values": a pending
 * {@code audio.backend} edit is the settings catalogue's only
 * {@code RESTART_REQUIRED} descriptor (Settings View Design Book §6.4), so it
 * must never reach a live {@code applyConfiguration}, and neither may the
 * device names chosen for the backend it is waiting on.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SettingsDialogRequestedEndpointTest {

    private static final String INPUT = "Interface In";
    private static final String OUTPUT = "Interface Out";
    private static final String OTHER_INPUT = "Monitor In";
    private static final String OTHER_OUTPUT = "Monitor Out";

    /**
     * A controller whose ladder has FALLEN BACK: the user asked for ASIO,
     * Java Sound won the open, and the provisioned query therefore answers the
     * fallback for as long as that stream lasts. Both backends enumerate the
     * same device names so the asynchronous device enumeration never resets a
     * seeded row out from under an Apply.
     */
    private static Story307TestSupport.StubController fallenBackController() {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.activeBackend = "Java Sound";
        controller.provisionedBackend = "Java Sound";
        List<AudioDeviceInfo> devices = List.of(
                device(INPUT), device(OUTPUT), device(OTHER_INPUT), device(OTHER_OUTPUT));
        controller.devicesByBackend.put("ASIO", devices);
        controller.devicesByBackend.put("Java Sound", devices);
        return controller;
    }

    /** Persists the ASIO endpoint the user asked for and the engine was configured with. */
    private static SettingsModel modelRequestingAsio(String prefix) {
        SettingsModel model = Story307TestSupport.model(prefix);
        model.setAudioBackend("ASIO");
        model.setAudioInputDevice(INPUT);
        model.setAudioOutputDevice(OUTPUT);
        return model;
    }

    /**
     * The transport-state-dependence bug. Two buffer-size-only Applies with a
     * transport stop in between: the provisioned answer flips from the
     * streaming fallback to the ladder's head rung, and the request must not
     * move at all. Against the pre-fix code the first request named
     * {@code "Java Sound"} with blanked device names and the second named
     * {@code "ASIO"} with the persisted ones — two different endpoints for two
     * identical edits.
     */
    @Test
    void bufferOnlyApplyTargetsThePersistedBackendNotTheStreamingFallback()
            throws Exception {
        Story307TestSupport.StubController controller = fallenBackController();
        SettingsModel model = modelRequestingAsio("requestedEndpointBufferOnly");
        int firstBuffer = model.getBufferSize() == 512 ? 256 : 512;
        int secondBuffer = firstBuffer == 512 ? 1024 : 512;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(firstBuffer);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();

        // Transport stops. The open stream no longer holds the device, so the
        // provision reverts to its head rung and the provisioned query starts
        // answering the requested backend instead of the fallback.
        controller.activeBackend = AudioEngineController.BACKEND_NONE;
        controller.provisionedBackend = "ASIO";
        controller.configurationApplied = new CountDownLatch(1);

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(secondBuffer);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(controller.requests).hasSize(2);
        assertThat(controller.requests)
                .extracting(AudioEngineController.Request::backendName)
                .as("a buffer-size change targets the backend the user asked for, never "
                        + "the rung the fallback ladder happens to be streaming")
                .containsExactly("ASIO", "ASIO");
        assertThat(controller.requests)
                .extracting(AudioEngineController.Request::inputDeviceName)
                .as("the requested devices are not blanked just because the ladder fell back")
                .containsExactly(INPUT, INPUT);
        assertThat(controller.requests)
                .extracting(AudioEngineController.Request::outputDeviceName)
                .containsExactly(OUTPUT, OUTPUT);
        assertThat(controller.requests)
                .extracting(AudioEngineController.Request::bufferFrames)
                .as("only the edited fact differs between the two requests")
                .containsExactly(firstBuffer, secondBuffer);
    }

    /**
     * The persist/reopen divergence. One Apply changes the output device AND
     * the buffer size while the provisioned backend differs from the persisted
     * one. Against the pre-fix code the request reopened the OLD (in fact
     * blanked) endpoint while the same Apply persisted the NEW device name,
     * leaving the live stream and the saved settings disagreeing.
     */
    @Test
    void deviceEditReachesTheEngineAndThePersistedSettingsTogether() throws Exception {
        Story307TestSupport.StubController controller = fallenBackController();
        SettingsModel model = modelRequestingAsio("requestedEndpointDeviceEdit");
        int changedBuffer = model.getBufferSize() == 512 ? 256 : 512;

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.outputDevice").orElseThrow()
                    .setValue(OTHER_OUTPUT);
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(changedBuffer);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Story307TestSupport.awaitFxValue(
                model::getAudioOutputDevice, OTHER_OUTPUT, 5, TimeUnit.SECONDS)).isTrue();

        AudioEngineController.Request request = controller.lastRequest.get();
        assertThat(request.outputDeviceName())
                .as("the engine is asked to open the device the user just chose")
                .isEqualTo(OTHER_OUTPUT);
        assertThat(request.outputDeviceName())
                .as("the reopened endpoint and the persisted setting must not diverge")
                .isEqualTo(model.getAudioOutputDevice());
        assertThat(request.inputDeviceName())
                .as("the untouched input row keeps the persisted device")
                .isEqualTo(INPUT);
        assertThat(request.backendName())
                .as("a device edit does not retarget the backend either")
                .isEqualTo("ASIO");
        assertThat(request.bufferFrames()).isEqualTo(changedBuffer);
    }

    /**
     * The RESTART_REQUIRED contract. A pending {@code audio.backend} edit is
     * persisted for the next launch and never handed to a live
     * {@code applyConfiguration}, and the device names picked for that
     * awaiting backend are suppressed with it.
     *
     * <p>The pending backend is deliberately the SAME name the provisioned
     * query answers, which is the trap this test exists for: reading the
     * backend from the provisioned fact and reading it from the pending edit
     * are indistinguishable here, and both are wrong. Only the applied
     * backend is right.</p>
     */
    @Test
    void pendingBackendEditNeitherReachesTheRequestNorDragsItsDevicesAlong()
            throws Exception {
        Story307TestSupport.StubController controller = fallenBackController();
        SettingsModel model = modelRequestingAsio("requestedEndpointPendingBackend");
        int changedBuffer = model.getBufferSize() == 512 ? 256 : 512;

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.backend").orElseThrow()
                    .setValue("Java Sound");
            dialog.getShell().settingRow("audio.inputDevice").orElseThrow()
                    .setValue(OTHER_INPUT);
            dialog.getShell().settingRow("audio.outputDevice").orElseThrow()
                    .setValue(OTHER_OUTPUT);
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(changedBuffer);
            assertThat(dialog.getShell().isRestartBannerVisible())
                    .as("switching the driver stack is a restart, not a re-arm")
                    .isTrue();
            dialog.applySettings();
            return null;
        });
        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Story307TestSupport.awaitFxValue(
                model::getAudioBackend, "Java Sound", 5, TimeUnit.SECONDS)).isTrue();

        assertThat(model.getAudioInputDevice())
                .as("the future backend's endpoint is persisted for the next launch")
                .isEqualTo(OTHER_INPUT);
        assertThat(model.getAudioOutputDevice()).isEqualTo(OTHER_OUTPUT);

        AudioEngineController.Request request = controller.lastRequest.get();
        assertThat(request.backendName())
                .as("the applied backend, not the pending one and not the provisioned one "
                        + "— which are the same name here precisely so that reading "
                        + "either would look correct")
                .isEqualTo("ASIO");
        assertThat(request.inputDeviceName())
                .as("device names chosen for a backend awaiting restart never reach the "
                        + "backend that is running")
                .isEqualTo(INPUT);
        assertThat(request.outputDeviceName()).isEqualTo(OUTPUT);
        assertThat(request.bufferFrames())
                .as("the re-armable part of the same Apply still goes live")
                .isEqualTo(changedBuffer);
    }

    /**
     * The capability path is untouched by the split.
     * {@code DefaultAudioEngineController.setSampleRate(backend, outputDevice,
     * rate)} builds a throwaway backend from the NAME and hands it a
     * {@code DeviceId} stamped with that same name, so its pair has to stay
     * internally consistent: the
     * PROVISIONED backend, and a device name that belongs to it — blank here,
     * because the persisted names belong to the requested backend instead.
     * Both facts are the pre-fix behaviour, deliberately unchanged.
     */
    @Test
    void sampleRateStillTargetsTheProvisionedBackendWithADeviceThatBelongsToIt()
            throws Exception {
        Story307TestSupport.StubController controller = fallenBackController();
        SettingsModel model = modelRequestingAsio("requestedEndpointSampleRate");
        double changedRate = model.getSampleRate() == 96_000 ? 48_000 : 96_000;

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.sampleRate").orElseThrow()
                    .setValue(changedRate);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.sampleRateAttempted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(controller.sampleRateCalls).hasSize(1);
        Story307TestSupport.StubController.SampleRateCall call =
                controller.sampleRateCalls.getFirst();
        assertThat(call.backendName())
                .as("a driver-level call must reach the driver that is actually there")
                .isEqualTo("Java Sound");
        assertThat(call.outputDeviceName())
                .as("a device name belonging to the requested backend must not be "
                        + "handed to the provisioned one")
                .isEmpty();
        assertThat(call.rate()).isEqualTo(changedRate);

        assertThat(controller.lastRequest.get().backendName())
                .as("the same Apply still asks the engine to open the REQUESTED endpoint")
                .isEqualTo("ASIO");
    }

    /**
     * The other half of the capability rule, also unchanged: when the
     * provisioned backend IS the persisted one, a pending output-device edit
     * belongs to it and the sample-rate call adopts it.
     */
    @Test
    void sampleRateAdoptsAPendingOutputDeviceThatBelongsToTheProvisionedBackend()
            throws Exception {
        Story307TestSupport.StubController controller = fallenBackController();
        SettingsModel model = Story307TestSupport.model("requestedEndpointAlignedSampleRate");
        model.setAudioBackend("Java Sound");
        model.setAudioInputDevice(INPUT);
        model.setAudioOutputDevice(OUTPUT);
        double changedRate = model.getSampleRate() == 96_000 ? 48_000 : 96_000;

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.outputDevice").orElseThrow()
                    .setValue(OTHER_OUTPUT);
            dialog.getShell().settingRow("audio.sampleRate").orElseThrow()
                    .setValue(changedRate);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.sampleRateAttempted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(controller.sampleRateCalls).hasSize(1);
        Story307TestSupport.StubController.SampleRateCall call =
                controller.sampleRateCalls.getFirst();
        assertThat(call.backendName()).isEqualTo("Java Sound");
        assertThat(call.outputDeviceName())
                .as("the pending device belongs to the provisioned backend, so the "
                        + "driver call targets it")
                .isEqualTo(OTHER_OUTPUT);

        AudioEngineController.Request request = controller.lastRequest.get();
        assertThat(request.backendName()).isEqualTo("Java Sound");
        assertThat(request.outputDeviceName()).isEqualTo(OTHER_OUTPUT);
    }

    private static AudioDeviceInfo device(String name) {
        return new AudioDeviceInfo(0, name, "test", 2, 2, 48_000,
                List.of(SampleRate.fromHz(48_000)), 2, 2);
    }
}
