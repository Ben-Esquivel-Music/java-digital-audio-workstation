package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.Transport.ChangeKind;
import com.benesquivelmusic.daw.core.transport.TransportState;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The observable view-model mirror of {@link Transport} — Stage 2 of the §8
 * migration path (Control Synchronization Design Book §3.1, §3.2, §4.3).
 *
 * <p>{@code TransportVM} is the adapter that lets the JavaFX-free core be
 * observed without putting {@code javafx.beans.*} into {@code daw-core} (§2.5,
 * §3.2, §9). It registers the core's toolkit-neutral
 * {@link Transport#addChangeListener(Consumer) change signal} and, on each
 * {@link ChangeKind}, re-reads the affected slice and republishes it as a
 * read-only JavaFX {@code Property}. It is the <strong>single writer</strong> of
 * its properties (§2.4): the wrappers are private and only the exposed
 * {@code ReadOnly*Property} views are handed to controls, so a control can bind
 * but never write back — the feedback-guard smell of §1.4 cannot arise.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>The core signal may fire on any thread (a seek from the UI thread, an
 * {@code advancePosition} from the {@code @RealTimeSafe} render thread). Every
 * property write is therefore marshalled onto the FX thread through the story-289
 * {@link FxDispatcher} (§4.5):</p>
 * <ul>
 *   <li><strong>Discrete</strong> facts ({@link ChangeKind#STATE STATE},
 *       {@link ChangeKind#TEMPO TEMPO}, {@link ChangeKind#TIME_SIGNATURE
 *       TIME_SIGNATURE}, {@link ChangeKind#LOOP LOOP}) are posted with
 *       {@link FxDispatcher#onFx(Runnable)}.</li>
 *   <li>The <strong>continuous</strong> {@link #playhead} is published into the
 *       dispatcher's lock-free, single-reader buffer and delivered once per frame
 *       by the drain (§4.5, §5.2) — never a per-tick {@code runLater}. The
 *       producer side ({@code publish}) is wait-free, safe to call from the audio
 *       thread (§4.1, §4.6).</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The constructor registers the core signal and seeds every property with the
 * current transport state. {@link #dispose()} unregisters the signal so no
 * listener leaks ({@code javafx-application-design} §3/§4/§11). The instance is
 * single-use; after {@code dispose()} it receives no further signals.</p>
 */
public final class TransportVM {

    private final Transport transport;
    private final FxDispatcher dispatcher;

    private final ReadOnlyObjectWrapper<TransportState> state =
            new ReadOnlyObjectWrapper<>(this, "state");
    private final ReadOnlyDoubleWrapper tempo =
            new ReadOnlyDoubleWrapper(this, "tempo");
    private final ReadOnlyObjectWrapper<TimeSignature> timeSignature =
            new ReadOnlyObjectWrapper<>(this, "timeSignature");
    private final ReadOnlyObjectWrapper<LoopRegion> loopRegion =
            new ReadOnlyObjectWrapper<>(this, "loopRegion");
    private final ReadOnlyDoubleWrapper playhead =
            new ReadOnlyDoubleWrapper(this, "playhead");

    /** Lock-free, single-reader buffer feeding the continuous {@link #playhead} (§4.5). */
    private final FxDispatcher.ContinuousChannel<Double> playheadChannel;

    /** Removal token returned by {@link Transport#addChangeListener(Consumer)}. */
    private final Runnable unregister;

    private boolean disposed;

    /**
     * Creates a view-model bound to {@code transport}, marshalling all property
     * writes through {@code dispatcher}.
     *
     * @param transport  the authoritative transport to mirror; must not be {@code null}
     * @param dispatcher the marshalling seam (story 289); must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public TransportVM(Transport transport, FxDispatcher dispatcher) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");

        this.playheadChannel = dispatcher.openContinuous(playhead::set);

        // Seed every property with the current state so a control binding shows
        // the correct value before the first signal arrives.
        state.set(transport.getState());
        tempo.set(transport.getTempo());
        timeSignature.set(readTimeSignature());
        loopRegion.set(readLoopRegion());
        playhead.set(transport.getPositionInBeats());

        this.unregister = transport.addChangeListener(this::onCoreChange);
    }

    /**
     * Handles a core change signal. Runs on whatever thread mutated the
     * transport; routes each discrete fact onto the FX thread and the continuous
     * playhead through the lock-free buffer (§4.5).
     */
    private void onCoreChange(ChangeKind kind) {
        switch (kind) {
            // Stop also resets the position to the anchor, so a STATE change
            // republishes the playhead too (§5.2 "state, playhead→anchor").
            case STATE -> {
                dispatcher.onFx(() -> state.set(transport.getState()));
                playheadChannel.publish(transport.getPositionInBeats());
            }
            case TEMPO -> dispatcher.onFx(() -> tempo.set(transport.getTempo()));
            case TIME_SIGNATURE -> dispatcher.onFx(() -> timeSignature.set(readTimeSignature()));
            case LOOP -> dispatcher.onFx(() -> loopRegion.set(readLoopRegion()));
            case POSITION -> playheadChannel.publish(transport.getPositionInBeats());
        }
    }

    private TimeSignature readTimeSignature() {
        return new TimeSignature(
                transport.getTimeSignatureNumerator(),
                transport.getTimeSignatureDenominator());
    }

    private LoopRegion readLoopRegion() {
        return new LoopRegion(
                transport.isLoopEnabled(),
                transport.getLoopStartInBeats(),
                transport.getLoopEndInBeats());
    }

    // ── Read-only property views (the VM is the sole writer) ──────────────────

    /** The current transport state. Read-only; bound by the play/record/stop controls. */
    public ReadOnlyObjectProperty<TransportState> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /** Returns the current transport state. */
    public TransportState getState() {
        return state.get();
    }

    /** The initial tempo in BPM. Read-only; bound by the tempo display. */
    public ReadOnlyDoubleProperty tempoProperty() {
        return tempo.getReadOnlyProperty();
    }

    /** Returns the current tempo in BPM. */
    public double getTempo() {
        return tempo.get();
    }

    /** The initial time signature. Read-only; bound by the time-signature display. */
    public ReadOnlyObjectProperty<TimeSignature> timeSignatureProperty() {
        return timeSignature.getReadOnlyProperty();
    }

    /** Returns the current time signature. */
    public TimeSignature getTimeSignature() {
        return timeSignature.get();
    }

    /** The loop region. Read-only; bound by the loop toggle and canvas overlay. */
    public ReadOnlyObjectProperty<LoopRegion> loopRegionProperty() {
        return loopRegion.getReadOnlyProperty();
    }

    /** Returns the current loop region. */
    public LoopRegion getLoopRegion() {
        return loopRegion.get();
    }

    /**
     * The continuous playback position in beats. Read-only; bound by the
     * playhead/time display. Updated once per frame via the dispatcher drain.
     */
    public ReadOnlyDoubleProperty playheadProperty() {
        return playhead.getReadOnlyProperty();
    }

    /** Returns the current playhead position in beats. */
    public double getPlayhead() {
        return playhead.get();
    }

    /**
     * Unregisters the core change signal so no listener leaks. Idempotent —
     * a second call is a no-op. After disposal the VM receives no further
     * signals; its properties retain their last values.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        unregister.run();
    }
}
