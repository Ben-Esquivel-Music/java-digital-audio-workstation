package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.diagnostics.AudioHealthDrain;
import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The window's FX reactions to a device loss (story 338), handed every
 * engine-state change by the {@link EngineStateBridge} before the transport
 * gets it. At a {@link EngineState#DEVICE_LOST} delivery, whatever the
 * loss's cause:
 * <ul>
 *   <li>the audio health drain is told the render-drive epoch the engine
 *       controller read as it declared the loss
 *       ({@link DefaultAudioEngineController#lostDriveEpoch()}), so that
 *       from this delivery on no device-side count it reads on that drive
 *       or an older one escalates or joins a sustained run, and a fault
 *       latched for such a drive is cleared when every category it latched
 *       is device-side (story 338, rulings E68 to E70 and E159);</li>
 *   <li>the input meters fall to the floor, each keeping its clip latch,
 *       instead of freezing at their last level (rulings E35 and E64); the
 *       output meters fall by themselves once no block arrives.</li>
 * </ul>
 * A window without a drain or an input-meter registry skips that reaction.
 * The bridge runs these before the window's engine-health model and the
 * transport's reaction, in the same FX turn. Each reaction is isolated: one
 * that throws is logged and the other still runs, and so does whatever the
 * bridge hands the change to after them.
 *
 * <p>It also answers, on the FX thread, whether the loss rescued a take
 * ({@link #takeRescued()}): the engine controller reads it when the person
 * chooses Reconnect (amendment A13).</p>
 *
 * <p>FX thread.</p>
 */
final class DeviceLossReactions implements Consumer<EngineStateChange> {

    private static final Logger LOG = Logger.getLogger(DeviceLossReactions.class.getName());

    private final Supplier<TransportController> currentTransport;
    private final LongSupplier lostDriveEpoch;
    private final Supplier<Optional<AudioHealthDrain>> healthDrain;
    private final InputLevelMonitorRegistry inputMeters;

    /**
     * Reactions for one window.
     *
     * @param currentTransport the window's current transport controller, or a supplier of {@code null} while none
     * @param lostDriveEpoch   the render-drive epoch the engine controller read as it declared its latest loss
     * @param healthDrain      the window's audio health drain, while it has one
     * @param inputMeters      the window's input-meter registry, or {@code null} when it has none
     */
    DeviceLossReactions(Supplier<TransportController> currentTransport, LongSupplier lostDriveEpoch,
                        Supplier<Optional<AudioHealthDrain>> healthDrain, InputLevelMonitorRegistry inputMeters) {
        this.currentTransport = Objects.requireNonNull(currentTransport, "currentTransport must not be null");
        this.lostDriveEpoch = Objects.requireNonNull(lostDriveEpoch, "lostDriveEpoch must not be null");
        this.healthDrain = Objects.requireNonNull(healthDrain, "healthDrain must not be null");
        this.inputMeters = inputMeters;
    }

    @Override
    public void accept(EngineStateChange change) {
        if (change.state() != EngineState.DEVICE_LOST) {
            return;
        }
        try {
            long epoch = lostDriveEpoch.getAsLong();
            healthDrain.get().ifPresent(drain -> drain.streamLost(epoch));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The device-loss reaction of the audio health drain failed", e);
        }
        try {
            if (inputMeters != null) {
                inputMeters.silenceAll();
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The device-loss reaction of the input meters failed", e);
        }
    }

    /**
     * Whether the current transport's record coordinator holds a take the
     * loss rescued: it is DEVICE_LOST, which only a take that was recording
     * enters. A loss during a count-in or a take's preparation rescued
     * nothing. FX thread; never blocks.
     *
     * @return whether a rescued take is waiting
     */
    boolean takeRescued() {
        TransportController transport = currentTransport.get();
        return transport != null && transport.recordCoordinator().getState() == RecordState.DEVICE_LOST;
    }
}
