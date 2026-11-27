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
 * {@link EngineState#RECONFIGURING} (published at the start of every Settings
 * apply and restart, and of a driver format change) and a closed-stream
 * {@code STOPPED}
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
 * <p>A {@link EngineState#DEVICE_LOST} also says how the device was lost
 * (story 338, rulings E5 and E113): the host reported it removed, the
 * engine's heartbeat watchdog found it stopped responding, or the driver
 * asked to be reset and the stream could not be reopened. Every other state
 * carries
 * {@link LossCause#NONE}.</p>
 *
 * @param state  the state just entered; never {@code null}
 * @param device the device watched at the transition, if any; never
 *               {@code null}
 * @param cause  how the device was lost: {@link LossCause#NONE} exactly when
 *               {@code state} is not {@link EngineState#DEVICE_LOST}
 */
public record EngineStateChange(EngineState state, Optional<DeviceId> device, LossCause cause) {

    /** How a {@link EngineState#DEVICE_LOST} came about. */
    public enum LossCause {
        /** Not a loss. */
        NONE,
        /** The host reported the device removed. */
        REMOVED,
        /** The device's heartbeat stopped advancing while its stream ran. */
        STALLED,
        /** The driver asked to be reset, and the stream open before the reset could not be reopened. */
        REOPEN_AFTER_RESET_FAILED
    }

    public EngineStateChange {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        if ((state == EngineState.DEVICE_LOST) != (cause != LossCause.NONE)) {
            throw new IllegalArgumentException("a loss cause goes with DEVICE_LOST only: " + state + ", " + cause);
        }
    }

    /**
     * A change whose loss, if it is one, is a removal.
     *
     * @param state  the state just entered
     * @param device the device watched at the transition, if any
     */
    public EngineStateChange(EngineState state, Optional<DeviceId> device) {
        this(state, device, defaultCause(state));
    }

    /** {@link LossCause#REMOVED} for {@link EngineState#DEVICE_LOST}, else {@link LossCause#NONE}. */
    static LossCause defaultCause(EngineState state) {
        return state == EngineState.DEVICE_LOST ? LossCause.REMOVED : LossCause.NONE;
    }
}
