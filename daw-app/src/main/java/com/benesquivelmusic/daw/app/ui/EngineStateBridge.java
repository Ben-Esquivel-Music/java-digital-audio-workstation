package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Story 327 — the bridge from the engine's lifecycle to the record state
 * machine (Recording Reliability book §6.5): the one subscription to
 * {@link AudioEngineController#engineStateEvents()}. Each
 * {@link EngineStateChange} — the state and the device the controller
 * watched at that transition, captured together under the controller's
 * monitor — is delivered on the publisher's executor and posted to the FX
 * thread, where the transport controller current at delivery time receives
 * exactly that pair through {@link TransportController#onEngineState}:
 * {@link EngineState#DEVICE_LOST} is the loss of the device it carries, and
 * the first {@link EngineState#STOPPED} or {@link EngineState#RUNNING}
 * delivered after it with the engine's stream open is the end of the loss,
 * on the device it carries (the rule is documented on
 * {@code onEngineState}). Nothing is looked up at delivery (PR #982 review):
 * a fast reopen may rebind the controller's watch to another device before
 * the FX turn of a queued DEVICE_LOST runs, and a bridge that read the
 * watch then delivered the loss of one device as the loss of another. The
 * producers of an open-stream state are the controller's
 * {@code onDeviceArrived} (the lost device returned: it publishes the
 * STOPPED after parking the reopened stream) and its stream-open seam,
 * {@code onStreamOpened}, for an open the controller did not drive — while
 * DEVICE_LOST, Play and Record are refused, so that is the instrument-audio
 * open of {@code MainController.pluginGraphChanged}, which opens whichever
 * rung the provision ladder yields — another device while the interface is
 * unplugged, or the lost device itself if it is physically back (the ladder
 * honours the configured {@code DeviceId}); the coordinator identity-matches
 * the device delivered with the state and treats a mismatch as the loss
 * ending elsewhere and a match as the return.
 *
 * <p>Requests everything and never blocks — the controller offers states
 * with a drop handler, so a blocking subscriber would lose them — and is
 * subscribed before the first stream can open: the controller's
 * constructor publishes no state, so subscribing right after it misses
 * nothing. {@code MainController} subscribes it once and
 * {@linkplain #cancel() cancels} it in the window teardown; a test over a
 * real {@link DefaultAudioEngineController} and {@link TransportController}
 * wires the same object.</p>
 */
final class EngineStateBridge implements Flow.Subscriber<EngineStateChange> {

    private static final Logger LOG = Logger.getLogger(EngineStateBridge.class.getName());

    private final Supplier<TransportController> currentTransport;
    private final FxDispatcher dispatcher;
    private volatile Flow.Subscription subscription;

    /**
     * @param currentTransport the transport controller current when a state
     *                         is delivered; {@code null} drops the state
     * @param dispatcher       where FX work is posted; {@code null} uses the
     *                         app-scoped default
     */
    EngineStateBridge(Supplier<TransportController> currentTransport, FxDispatcher dispatcher) {
        this.currentTransport = Objects.requireNonNull(currentTransport, "currentTransport must not be null");
        this.dispatcher = dispatcher;
    }

    /** Subscribes this bridge to {@code controller}'s engine-state events. Any thread. */
    void subscribeTo(AudioEngineController controller) {
        Objects.requireNonNull(controller, "controller must not be null").engineStateEvents().subscribe(this);
    }

    /** Publisher's executor: requests everything. */
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = Objects.requireNonNull(subscription, "subscription must not be null");
        subscription.request(Long.MAX_VALUE);
    }

    /**
     * Publisher's executor: posts the snapshot — state and device as
     * captured at the transition — to the FX thread and returns.
     */
    @Override
    public void onNext(EngineStateChange change) {
        FxDispatcher.runOnFx(dispatcher, () -> {
            TransportController current = currentTransport.get();
            if (current != null) {
                current.onEngineState(change.state(), change.device());
            }
        });
    }

    /** Publisher's executor. */
    @Override
    public void onError(Throwable throwable) {
        LOG.log(Level.WARNING, "The engine-state bridge ended with an error", throwable);
    }

    /** Publisher's executor. */
    @Override
    public void onComplete() {
        // The controller's shutdown() closed its publisher. In the app the
        // bridge ends through cancel() in the window teardown instead:
        // shutdown() has no production caller; tests over a real controller
        // call it, and reach here.
    }

    /** Ends the subscription, if one was established. Any thread; idempotent. */
    void cancel() {
        Flow.Subscription current = subscription;
        if (current != null) {
            current.cancel();
        }
    }
}
