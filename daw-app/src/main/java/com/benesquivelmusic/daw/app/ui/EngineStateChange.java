package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.util.Objects;
import java.util.Optional;

/**
 * One element of {@link AudioEngineController#engineStateEvents()}: the
 * engine state just entered and the device the controller watched at the
 * instant of that transition (story 327 fix round, PR #982 review).
 *
 * <p>The snapshot rule: {@code device} is captured under the controller's
 * monitor in {@code DefaultAudioEngineController.setEngineState}, in program
 * order after any rebind the same transition did, never looked up when the
 * element is delivered — delivery runs later, on the publisher's executor
 * and then the FX thread, by which time a fast reopen may have rebound the
 * watch to another device. So {@link EngineState#DEVICE_LOST} carries the
 * lost device; {@link EngineState#STOPPED} and {@link EngineState#RUNNING}
 * carry the device the open stream is bound to by the engine's stream-open
 * seam ({@code bindBackendDeviceEvents} runs before the publication);
 * {@link EngineState#RECONFIGURING} and a closed-stream {@code STOPPED}
 * carry whatever was watched then; and the device is empty when nothing was
 * watched.</p>
 *
 * <p>One window in which the snapshot names a device the stream is not
 * bound to — not pinned, and not a regression if it is found (PR #982
 * round 2): an open the controller did not drive has completed
 * ({@code AudioEngine.isStreamOpen()} true) while its stream-open
 * announcement is still waiting on the controller's monitor when
 * {@code onDeviceArrived} runs for the lost device. That handler skips its
 * reopen and publishes {@code STOPPED} with the device watched THEN — the
 * lost one — before the seam rebinds the watch to the rung that opened, so
 * the stream that {@code STOPPED} is read against may be bound to another
 * device. See {@code DefaultAudioEngineController.onDeviceArrived}.</p>
 *
 * @param state  the state just entered; never {@code null}
 * @param device the device watched at the transition, if any; never
 *               {@code null}
 */
public record EngineStateChange(EngineState state, Optional<DeviceId> device) {

    public EngineStateChange {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(device, "device must not be null");
    }
}
