package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.Transport.ChangeKind;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.util.Objects;
import java.util.Optional;
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
 *       TIME_SIGNATURE}, {@link ChangeKind#LOOP LOOP}, {@link ChangeKind#PUNCH
 *       PUNCH}) are posted with
 *       {@link FxDispatcher#onFx(Runnable)} — except a {@code STATE} fired
 *       <em>off</em> the FX thread, which goes through a lock-free channel
 *       (next item). Story 328 (book §5.6): a count-in's record start runs
 *       {@code Transport.record()} on the RENDER thread, so its {@code STATE}
 *       reaches this listener there; {@code Platform.runLater} takes the
 *       toolkit's queue lock and allocates, which the render thread must
 *       never do. The thread test is {@link FxDispatcher#isFxThread()} — one
 *       volatile read — and the channel's consumer re-reads the live
 *       transport state, so a later FX-thread change (a Stop right after
 *       the fire) can never be overwritten by the earlier value.</li>
 *   <li>The <strong>continuous</strong> {@link #playhead} is published into the
 *       dispatcher's lock-free, single-reader primitive-{@code double} buffer and
 *       delivered once per frame by the drain (§4.5, §5.2) — never a per-tick
 *       {@code runLater}. The producer side ({@code publish}) is wait-free and
 *       allocation-free (no {@link Double} box), safe to call from the audio
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
    private final ReadOnlyObjectWrapper<RecordState> recordState = new ReadOnlyObjectWrapper<>(RecordState.IDLE);
    private final ReadOnlyBooleanWrapper recordAvailable = new ReadOnlyBooleanWrapper(true);

    public ReadOnlyObjectProperty<RecordState> recordStateProperty() { return recordState.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty recordAvailableProperty() { return recordAvailable.getReadOnlyProperty(); }
    public void bindRecordState(ReadOnlyObjectProperty<RecordState> source, ReadOnlyBooleanProperty availability) {
        recordState.unbind();
        recordAvailable.unbind();
        recordState.bind(source);
        recordAvailable.bind(availability);
    }

    private final ReadOnlyDoubleWrapper tempo =
            new ReadOnlyDoubleWrapper(this, "tempo");
    private final ReadOnlyObjectWrapper<TimeSignature> timeSignature =
            new ReadOnlyObjectWrapper<>(this, "timeSignature");
    private final ReadOnlyObjectWrapper<LoopRegion> loopRegion =
            new ReadOnlyObjectWrapper<>(this, "loopRegion");
    /**
     * Story 328 — the loop-record flag mirror (book §5.6). Its producer is
     * the transport's {@link ChangeKind#LOOP} signal, as the loop region's
     * is: {@code Transport.setLoopRecordEnabled} fires it.
     */
    private final ReadOnlyBooleanWrapper loopRecord =
            new ReadOnlyBooleanWrapper(this, "loopRecord");
    /**
     * Story 328 — the punch-in/out region mirror (book §5.6): empty when no
     * region is installed. Produced by {@link ChangeKind#PUNCH}, which the
     * command cascade fires through {@code Transport.setPunchRegion}.
     */
    private final ReadOnlyObjectWrapper<Optional<PunchRegion>> punchRegion =
            new ReadOnlyObjectWrapper<>(this, "punchRegion", Optional.empty());
    private final ReadOnlyDoubleWrapper playhead =
            new ReadOnlyDoubleWrapper(this, "playhead");

    /**
     * Lock-free, single-reader primitive-{@code double} buffer feeding the
     * continuous {@link #playhead} (§4.5). The {@code double} specialization
     * ({@link FxDispatcher#openContinuousDouble}) so a publish from the
     * {@code @RealTimeSafe} render path (via {@link Transport#advancePosition(double)})
     * allocates no {@link Double} box. Closed in {@link #dispose()}.
     */
    private final FxDispatcher.ContinuousDoubleChannel playheadChannel;

    /**
     * Lock-free, single-reader channel carrying a {@link ChangeKind#STATE}
     * fired off the FX thread — by the render thread at a count-in's end
     * (story 328) — to the pulse's drain, which re-reads the transport's
     * live state. The published value is the enum constant: no allocation.
     * Closed in {@link #dispose()}.
     */
    private final FxDispatcher.ContinuousChannel<ChangeKind> stateChannel;

    /**
     * The discrete facts' FX-thread republish runnables, built once here so
     * {@link #onCoreChange} contains no lambda: a capturing lambda per
     * signal is an allocation on whichever thread fires the signal (the
     * {@code ChannelVM} precedent, story 322 fix round 1).
     */
    private final Runnable republishState;
    private final Runnable republishTempo;
    private final Runnable republishTimeSignature;
    private final Runnable republishLoop;
    private final Runnable republishPunch;

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

        this.playheadChannel = dispatcher.openContinuousDouble(playhead::set);
        this.stateChannel = dispatcher.openContinuous(kind -> state.set(transport.getState()));
        this.republishState = () -> state.set(transport.getState());
        this.republishTempo = () -> tempo.set(transport.getTempo());
        this.republishTimeSignature = () -> timeSignature.set(readTimeSignature());
        this.republishLoop = () -> {
            loopRegion.set(readLoopRegion());
            loopRecord.set(transport.isLoopRecordEnabled());
        };
        this.republishPunch = () -> punchRegion.set(readPunchRegion());

        // Seed every property with the current state so a control binding shows
        // the correct value before the first signal arrives.
        state.set(transport.getState());
        tempo.set(transport.getTempo());
        timeSignature.set(readTimeSignature());
        loopRegion.set(readLoopRegion());
        loopRecord.set(transport.isLoopRecordEnabled());
        punchRegion.set(readPunchRegion());
        playhead.set(transport.getPositionInBeats());

        this.unregister = transport.addChangeListener(this::onCoreChange);
    }

    /**
     * Handles a core change signal. Runs on whatever thread mutated the
     * transport; routes each discrete fact onto the FX thread and the continuous
     * playhead through the lock-free buffer (§4.5).
     *
     * <p>The dispatch on {@code kind} is an identity if-chain, deliberately
     * <em>not</em> a {@code switch}, and {@code STATE} and {@code POSITION}
     * — the two kinds the render thread fires — come first (story 328
     * review round 2; the {@code ChannelVM.onCoreChange} precedent). An enum
     * {@code switch} over the cross-module {@code ChangeKind} compiles to a
     * lookup through a synthetic {@code TransportVM$1.$SwitchMap$…} table
     * whose class initialiser — an {@code int[]} allocation, a
     * {@code values()} clone and the class-initialisation lock — runs on
     * whichever thread reaches the switch first; after a fresh JVM with
     * Record pressed before any play or seek, that is the render thread at
     * the count-in's fire, the block before the first captured one
     * ({@code dawg-annotations-reflection} §4 — never allocate or lock on
     * the audio thread). {@code CountInRecordStartListenerBytecodeSentinelTest}
     * walks this method's bytecode: no allocation, no monitor, no toolkit
     * entry, no switch map, no lambda.</p>
     */
    private void onCoreChange(ChangeKind kind) {
        if (kind == ChangeKind.STATE) {
            // Stop also resets the position to the anchor, so a STATE change
            // republishes the playhead too (§5.2 "state, playhead→anchor").
            if (dispatcher.isFxThread()) {
                dispatcher.onFx(republishState);
            } else {
                // The render thread's record start (story 328) lands
                // here: no runLater, no lambda — a wait-free publish.
                stateChannel.publish(kind);
            }
            playheadChannel.publish(transport.getPositionInBeats());
        } else if (kind == ChangeKind.POSITION) {
            playheadChannel.publish(transport.getPositionInBeats());
        } else if (kind == ChangeKind.TEMPO) {
            dispatcher.onFx(republishTempo);
        } else if (kind == ChangeKind.TIME_SIGNATURE) {
            dispatcher.onFx(republishTimeSignature);
        } else if (kind == ChangeKind.LOOP) {
            dispatcher.onFx(republishLoop);
        } else if (kind == ChangeKind.PUNCH) {
            dispatcher.onFx(republishPunch);
        }
    }

    /** Reads the punch region as one snapshot — a single volatile load of the record reference. */
    private Optional<PunchRegion> readPunchRegion() {
        return Optional.ofNullable(transport.getPunchRegion());
    }

    private TimeSignature readTimeSignature() {
        return new TimeSignature(
                transport.getTimeSignatureNumerator(),
                transport.getTimeSignatureDenominator());
    }

    /**
     * Reads the loop trio as ONE snapshot (story 315 review). The three
     * individual getters each read the same volatile {@code LoopWindow}
     * reference separately, so a control write landing between them yields a
     * {@link LoopRegion} stitched from two different windows — an enabled flag
     * from one and bounds from another. {@link Transport#getLoopWindow()} is the
     * tear-free read the core added for exactly this.
     */
    private LoopRegion readLoopRegion() {
        Transport.LoopWindow window = transport.getLoopWindow();
        return new LoopRegion(
                window.enabled(),
                window.startInBeats(),
                window.endInBeats());
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
     * Whether loop-record is enabled (story 328, book §5.6). Read-only;
     * bound by the Loop Rec toggle; follows {@link ChangeKind#LOOP}.
     */
    public ReadOnlyBooleanProperty loopRecordProperty() {
        return loopRecord.getReadOnlyProperty();
    }

    /** Returns whether loop-record is enabled. */
    public boolean isLoopRecord() {
        return loopRecord.get();
    }

    /**
     * The punch-in/out region in PROJECT-rate frames, empty when none is
     * installed (story 328, book §5.6). Read-only; follows
     * {@link ChangeKind#PUNCH}.
     */
    public ReadOnlyObjectProperty<Optional<PunchRegion>> punchRegionProperty() {
        return punchRegion.getReadOnlyProperty();
    }

    /** Returns the current punch region, empty when none is installed. */
    public Optional<PunchRegion> getPunchRegion() {
        return punchRegion.get();
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
     * Unregisters the core change signal <em>and</em> closes the continuous
     * playhead channel so nothing leaks (story 290 AC: "{@code dispose()} that
     * unregisters and closes any subscription — no leaked listeners"). Closing
     * the channel matters because it lives in the long-lived, app-scoped
     * {@link FxDispatcher} and holds the FX consumer (hence this VM); leaving it
     * open would leak the VM across every create/dispose cycle, not just the
     * upstream listener ({@code javafx-application-design} §4/§11/§15). Idempotent
     * — a second call is a no-op. After disposal the VM receives no further
     * signals; its properties retain their last values.
     */
    public void dispose() {
        recordState.unbind();
        recordAvailable.unbind();
        if (disposed) {
            return;
        }
        disposed = true;
        unregister.run();
        playheadChannel.close();
        stateChannel.close();
    }
}
