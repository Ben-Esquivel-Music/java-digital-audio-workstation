package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.controls.InsertSlotModel;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.app.ui.metering.VisibleMeterBinding;
import com.benesquivelmusic.daw.core.metering.MeterFrame;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.MixerChannel.ChangeKind;
import com.benesquivelmusic.daw.core.mixer.SpatialInserts;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The observable view-model mirror of a {@link MixerChannel} — the mixer-strip
 * half of story 291 (Control Synchronization Design Book §1.3, §3.1, §3.2,
 * §4.3, §4.5). {@code ChannelVM} projects the channel's continuous fader/pan
 * values, its discrete mute/solo flags, its insert chain, a per-frame meter
 * level, and a <em>derived</em> effective-mute flag.
 *
 * <p>{@code ChannelVM} is the adapter that lets the JavaFX-free core be observed
 * without putting {@code javafx.beans.*} into {@code daw-core} (§2.5, §3.2, §9).
 * It registers the core's toolkit-neutral
 * {@link MixerChannel#addChangeListener(Consumer) change signal} and, on each
 * {@link ChangeKind} tag, re-reads the affected slice and republishes it as a
 * read-only JavaFX {@code Property}. It is the <strong>single writer</strong> of
 * its properties (§2.4): the wrappers are private and only the exposed
 * {@code ReadOnly*Property} views are handed to controls, so a control can bind
 * but never write back (§1.4, §4.4).</p>
 *
 * <h2>Mute/solo: own flags versus effective mute (story 322)</h2>
 *
 * <p>Since story 322 the channel's <em>own</em> mute/solo are projected as
 * discrete read-only {@link #mutedProperty()} / {@link #soloedProperty()} facts
 * — a return bus, the master and a VCA member without a track have no
 * {@code TrackVM} to bind to, so the mixer strip binds these (Audio Engine
 * Wiring Design Book §5.6 "Mixer strip vol/pan/mute/solo"). What the strip
 * additionally needs is the <em>effective</em> mute — whether the channel is
 * silenced either by its own mute or by another channel's solo. That value
 * depends on project-wide solo state, so {@link TrackChannelRegistry} (which
 * sees every channel) owns its recompute and pushes it in via
 * {@link #recomputeEffectiveMute(boolean)}.</p>
 *
 * <h2>Threading — RT-safety of a live registry (story 322 §2.2)</h2>
 *
 * <p>The core signal fires on whatever thread mutated the channel, and for
 * {@code VOLUME}/{@code PAN} that is the <strong>audio thread</strong>:
 * {@code RenderPipeline.applyAutomation} drives the channel setters every block.
 * Those two arms therefore never post a {@code Platform.runLater}. When the
 * signal arrives on the FX thread the property is set inline (so a binder's
 * stateless echo guard sees the new value synchronously — the
 * {@code ProjectVM.applyOnFx} precedent); otherwise a tick is published into a
 * dedicated {@link FxDispatcher#openContinuousDouble(java.util.function.DoubleConsumer)
 * continuous channel} — wait-free and allocation-free, the same primitive that
 * carries {@link #meterLevel} — and the FX-pulse drain <em>re-reads the
 * channel</em>. Re-reading (rather than applying the published payload) makes an
 * inline FX write interleaved with a still-queued off-thread tick converge on the
 * current authority regardless of order.</p>
 *
 * <p>The "am I on the FX thread" test is itself part of that RT budget. It is
 * {@link FxDispatcher#isFxThread()} — a plain compare against the thread the
 * dispatcher recorded at {@code start()} — and <strong>never</strong>
 * {@code Platform.isFxApplicationThread()}: the toolkit's query goes through
 * the {@code static synchronized Toolkit.getToolkit()} and so takes the
 * {@code Toolkit.class} monitor on every call, a lock the FX thread holds on
 * every pulse (Audio Engine Wiring Design Book §6.1 — the RT callback never
 * locks). For the same reason the non-FX branch of {@link #onCoreChange}
 * allocates nothing: the {@code VOLUME}/{@code PAN} arms are written out
 * without a lambda, and the discrete arms hand {@link #applyOnFx} runnables
 * built once in the constructor. Two sentinels keep this true —
 * {@code VmFxThreadQueryScanTest} (no toolkit thread query anywhere in the VM
 * layer) and {@code ChannelVmRtPathBytecodeSentinelTest} (no
 * {@code invokedynamic}, {@code new}, {@code monitorenter}, {@code Platform}
 * call, enum {@code $SwitchMap$} access or synthetic nested-class access
 * reachable from {@code onCoreChange} within this class — see that method
 * for why it dispatches without a {@code switch}).</p>
 *
 * <p>{@code MUTE}/{@code SOLO} republish inline on the FX thread and otherwise
 * through {@link FxDispatcher#onFx(Runnable)}. A {@code runLater} per signal
 * (which allocates inside the toolkit) is acceptable there <strong>only
 * because</strong> {@link MixerChannel#setMuted} / {@link MixerChannel#setSolo}
 * (story 322) notify solely when the flag actually flips — an automation lane
 * re-asserting the same value every block produces no signal, so the cost is
 * edge-triggered and no per-block posting can reach the FX queue. Do not
 * relax that setter invariant without revisiting these arms.</p>
 *
 * <p>{@code INSERTS} is never fired on the audio thread (chain edits are UI /
 * undo work). It fires from inside {@code MixerChannel.rebuildEffectsChain()}
 * with the channel's monitor held, so the callback does only lock-free work:
 * an inline rebuild of the insert facts on the FX thread, else an
 * {@code onFx} hop. It never calls back into the channel's insert mutators.</p>
 *
 * <h2>The meter producer (story 318)</h2>
 *
 * <p>{@link #meterLevel} has a live producer: {@link #bindMeter(Node)}
 * subscribes this channel's post-fader {@link MeterTapPoint.ChannelPost} tap on
 * the engine's metering tap bus (Audio Engine Wiring Design Book §3.3, §4.3).
 * The render thread writes peak / RMS into a preallocated slot; the FX pulse
 * reads the newest coherent frame once per frame and publishes its peak, in
 * <strong>dBFS with a {@value #METER_FLOOR_DB} dB floor</strong>, through the
 * continuous channel — so the value a strip binds is exactly the level the
 * engine rendered, and it falls to the floor at stop (the feed's silent
 * frame). A surface owns its binding and supplies scene/window/visibility
 * demand; a project-owned VM alone acquires no subscription.
 * {@link #unbindMeter()} — and {@link #dispose()} — release every surface's
 * binding. Binding is optional: an unbound VM keeps its floor value, which
 * is what a pure-unit context or a project with no engine shows.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The constructor registers the core signal, opens the three continuous
 * channels (volume, pan, meter), and seeds every property. {@link #dispose()}
 * unregisters the signal and closes the channels so nothing leaks
 * ({@code javafx-application-design} §3/§4/§11). Idempotent and single-use.</p>
 */
public final class ChannelVM {

    /**
     * The {@link #meterLevelProperty() meterLevel} floor, in dBFS. Digital
     * silence is {@code -Infinity} dB, which no {@code double} property should
     * carry into a binding (and which the continuous channel would happily
     * relay into a layout calculation), so every published value — and the
     * seeded initial value — is clamped here. −120 dBFS is two orders of
     * magnitude below the −60 dB bottom of the app's meter scales, so the
     * clamp is never visible as a level.
     */
    public static final double METER_FLOOR_DB = -120.0;

    /**
     * The payload published into the volume/pan continuous channels from a
     * non-FX thread. The channels are depth-1 latest-wins mailboxes whose FX
     * drain re-reads the channel authority, so the payload is a mere "dirty"
     * tick; a constant (never {@code NaN}, the mailbox's empty sentinel) keeps
     * the producer trivially {@code @RealTimeSafe}.
     */
    private static final double REPUBLISH_TICK = 1.0;

    private final MixerChannel channel;
    private final FxDispatcher dispatcher;

    /** The channel id, seeded once at construction (the pairing key with {@link TrackVM}). */
    private final UUID channelId;

    private final ReadOnlyDoubleWrapper volume =
            new ReadOnlyDoubleWrapper(this, "volume");
    private final ReadOnlyDoubleWrapper pan =
            new ReadOnlyDoubleWrapper(this, "pan");
    private final ReadOnlyDoubleWrapper meterLevel =
            new ReadOnlyDoubleWrapper(this, "meterLevel");
    private final ReadOnlyBooleanWrapper muted =
            new ReadOnlyBooleanWrapper(this, "muted");
    private final ReadOnlyBooleanWrapper soloed =
            new ReadOnlyBooleanWrapper(this, "soloed");

    /**
     * Whether the channel is currently silenced — either by its own mute or by
     * another channel's solo (the audio engine's exact gate; see
     * {@link #recomputeEffectiveMute(boolean)}). Derived: written only by
     * {@link TrackChannelRegistry} because it needs project-wide solo state.
     */
    private final ReadOnlyBooleanWrapper effectiveMute =
            new ReadOnlyBooleanWrapper(this, "effectiveMute");

    /**
     * The channel's insert chain as immutable {@link InsertSlotModel}s, rebuilt
     * from {@link MixerChannel#getInsertSlots()} on every {@link ChangeKind#INSERTS}.
     * Only the unmodifiable {@link #insertsView} is exposed.
     */
    private final ObservableList<InsertSlotModel> inserts = FXCollections.observableArrayList();
    private final ObservableList<InsertSlotModel> insertsView =
            FXCollections.unmodifiableObservableList(inserts);

    /** Derived from the inserts on the same rebuild pass via {@link SpatialInserts#hasSpatialNode}. */
    private final ReadOnlyBooleanWrapper spatialNodePresent =
            new ReadOnlyBooleanWrapper(this, "spatialNodePresent");

    /**
     * Lock-free, single-reader primitive-{@code double} buffers (§4.5) — one
     * per continuous fact. The {@code double} specialization so an audio-thread
     * producer allocates no {@link Double} box. All closed in {@link #dispose()}.
     */
    private final FxDispatcher.ContinuousDoubleChannel meterChannel;
    private final FxDispatcher.ContinuousDoubleChannel volumeChannel;
    private final FxDispatcher.ContinuousDoubleChannel panChannel;

    /** Removal token returned by {@link MixerChannel#addChangeListener(Consumer)}. */
    private final Runnable unregister;

    /**
     * The discrete-fact republishes, built once in the constructor so the
     * {@code MUTE}/{@code SOLO}/{@code INSERTS} arms of {@link #onCoreChange}
     * hand {@link #applyOnFx} a pre-existing {@link Runnable} instead of
     * allocating a capturing lambda per signal — the arm may be entered on the
     * audio thread (see the class Javadoc, "Threading").
     */
    private final Runnable republishMuted;
    private final Runnable republishSoloed;
    private final Runnable republishInserts;

    private final MeterFeed meterFeed;
    private final Map<Node, VisibleMeterBinding> meterBindings = new IdentityHashMap<>();

    private boolean disposed;

    /**
     * Creates a view-model bound to {@code channel}, marshalling all property
     * writes through {@code dispatcher}.
     *
     * @param channel    the authoritative mixer channel to mirror; must not be {@code null}
     * @param dispatcher the marshalling seam (story 289); must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public ChannelVM(MixerChannel channel, FxDispatcher dispatcher) {
        this(channel, dispatcher, null);
    }

    /**
     * Supplies a meter feed without acquiring render demand. Each consuming
     * surface must call {@link #bindMeter(Node)} and release its returned token
     * when disposed. A {@code null} feed leaves this VM unmetered.
     */
    public ChannelVM(MixerChannel channel, FxDispatcher dispatcher, MeterFeed meterFeed) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.channelId = channel.getId();
        this.meterFeed = meterFeed;
        this.republishMuted = () -> muted.set(channel.isMuted());
        this.republishSoloed = () -> soloed.set(channel.isSolo());
        this.republishInserts = this::rebuildInserts;

        this.meterChannel = dispatcher.openContinuousDouble(meterLevel::set);
        // The drains re-read the authority (see the class Javadoc, "Threading").
        this.volumeChannel = dispatcher.openContinuousDouble(_ -> volume.set(channel.getVolume()));
        this.panChannel = dispatcher.openContinuousDouble(_ -> pan.set(channel.getPan()));

        // Seed every property with the current state so a control binding shows
        // the correct value before the first signal arrives. effectiveMute is
        // seeded by the registry once it knows project-wide solo state.
        volume.set(channel.getVolume());
        pan.set(channel.getPan());
        muted.set(channel.isMuted());
        soloed.set(channel.isSolo());
        rebuildInserts();
        // Story 318 — an unmetered channel reads as digital silence, not 0 dBFS
        // (which is full scale). Seeding the floor means a strip bound before
        // the first frame shows "no signal" rather than "clipping".
        meterLevel.set(METER_FLOOR_DB);

        this.unregister = channel.addChangeListener(this::onCoreChange);
    }

    /**
     * Subscribes this channel's post-fader {@code CHANNEL_POST} tap on
     * its configured feed so {@link #meterLevelProperty()} carries the level the
     * engine actually renders (story 318).
     *
     * <p>Each delivered {@code MeterFrame} contributes one value: the loudest
     * lane's peak in dBFS, clamped to {@value #METER_FLOOR_DB} (the continuous
     * channel rejects {@code NaN}, and {@code -Infinity} — digital silence —
     * must never reach a binding). Only a surface in a showing window, with
     * visible ancestors, owns a feed subscription. Hidden surfaces have no
     * render demand or per-pulse callback. Different surfaces bind independently;
     * binding the same surface again replaces only its previous binding.</p>
     *
     * @param surface the actual scene-graph consumer; must not be {@code null}
     * @return an idempotent removal token for this surface's binding only
     * @throws NullPointerException if {@code surface} is {@code null}
     * @throws IllegalStateException if this VM is disposed or has no meter feed
     *                               (see {@link #hasMeterFeed()})
     */
    public Runnable bindMeter(Node surface) {
        Objects.requireNonNull(surface, "surface must not be null");
        if (disposed) {
            throw new IllegalStateException("ChannelVM is disposed");
        }
        if (!hasMeterFeed()) {
            throw new IllegalStateException("ChannelVM has no active meter feed");
        }
        VisibleMeterBinding previous = meterBindings.remove(surface);
        if (previous != null) {
            previous.close();
        }
        var binding = new VisibleMeterBinding(meterFeed, new MeterTapPoint.ChannelPost(channelId),
                surface, frame -> meterChannel.publish(peakDbFloored(frame)));
        meterBindings.put(surface, binding);
        return () -> {
            meterBindings.remove(surface, binding);
            binding.close();
        };
    }

    /**
     * Whether {@link #bindMeter(Node)} can currently acquire a subscription —
     * {@code true} when this VM was built with a live (undisposed) feed. A
     * binder consults this before binding a strip's integrated meter so a
     * pure-unit VM (no engine, no feed) leaves the meter at its floor instead
     * of throwing (story 322).
     *
     * @return {@code true} if a live meter feed is configured
     */
    public boolean hasMeterFeed() {
        return meterFeed != null && !meterFeed.isDisposed();
    }

    /**
     * Releases every surface binding opened by {@link #bindMeter(Node)}.
     * Idempotent; a no-op when never bound. The last published level stays on
     * the property — the surface that unbinds decides what to show next.
     */
    public void unbindMeter() {
        for (VisibleMeterBinding binding : meterBindings.values()) {
            binding.close();
        }
        meterBindings.clear();
    }

    /** {@code true} while a surface is bound, including temporarily hidden surfaces. */
    public boolean isMeterBound() {
        return !meterBindings.isEmpty();
    }

    /**
     * The frame's loudest-lane peak in dBFS, floored at
     * {@value #METER_FLOOR_DB}. Equivalent to
     * {@code frame.toLevelData().peakDb()} without that method's per-frame
     * {@code LevelData} allocation — this runs on every FX pulse
     * ({@code javafx-application-design} §6).
     */
    private static double peakDbFloored(MeterFrame frame) {
        double peakDb = MeterFrame.toDb(frame.maxPeak());
        return Double.isNaN(peakDb) || peakDb < METER_FLOOR_DB ? METER_FLOOR_DB : peakDb;
    }

    /**
     * Handles a core change signal. Runs on whatever thread mutated the channel
     * — for {@code VOLUME}/{@code PAN} possibly the audio thread — and routes
     * each fact onto the FX thread by the discipline described in the class
     * Javadoc ("Threading"): a continuous fact inline on the FX thread, else a
     * wait-free tick into its channel (never {@code onFx} — this path is driven
     * per block by automation on the audio thread, story 322 §2.2); a discrete
     * fact inline-or-{@code onFx} through a runnable built once in the
     * constructor. Written out arm by arm, without a lambda, so nothing
     * reachable from here allocates, takes a monitor or enters the toolkit on
     * the non-FX branch — {@code ChannelVmRtPathBytecodeSentinelTest} walks
     * this method's bytecode to keep it that way. {@link #effectiveMute} is
     * not touched here; the registry recomputes it project-wide from its own
     * listener.
     *
     * <p>The dispatch on {@code kind} is an identity if-chain over the enum
     * constants, deliberately <em>not</em> a {@code switch} (fix round 2): an
     * enum {@code switch} compiles to a lookup through a synthetic
     * {@code ChannelVM$1.$SwitchMap$…} table whose class initialiser — an
     * {@code int[]} allocation, a {@code ChangeKind.values()} clone and the
     * class-initialisation lock — runs on whichever thread reaches the switch
     * first. The constructor registers this listener last, so after a fresh
     * JVM that thread can be the audio thread on the first automation signal
     * (Audio Engine Wiring Design Book §6.1 — the RT callback never allocates
     * or locks; {@code dawg-annotations-reflection} §4 — never allocate on the
     * audio thread). The if-chain is {@code getstatic} of the already
     * initialised constants plus {@code if_acmpne}: nothing to initialise, no
     * synthetic class, and the sentinel rejects any {@code $SwitchMap$}
     * access so the switch cannot come back.</p>
     */
    private void onCoreChange(ChangeKind kind) {
        if (kind == ChangeKind.VOLUME) {
            if (dispatcher.isFxThread()) {
                volume.set(channel.getVolume());
            } else {
                volumeChannel.publish(REPUBLISH_TICK);
            }
        } else if (kind == ChangeKind.PAN) {
            if (dispatcher.isFxThread()) {
                pan.set(channel.getPan());
            } else {
                panChannel.publish(REPUBLISH_TICK);
            }
        } else if (kind == ChangeKind.MUTE) {
            applyOnFx(republishMuted);
        } else if (kind == ChangeKind.SOLO) {
            applyOnFx(republishSoloed);
        } else if (kind == ChangeKind.INSERTS) {
            applyOnFx(republishInserts);
        }
    }

    /**
     * Inline when already on the FX thread, otherwise marshalled through
     * {@link FxDispatcher#onFx(Runnable)} — the {@code ProjectVM.applyOnFx}
     * precedent. The FX-thread test is {@link FxDispatcher#isFxThread()}, the
     * lock-free field compare (see the class Javadoc, "Threading"). Every
     * write re-reads the authority, so an inline update interleaved with a
     * still-queued off-thread one converges regardless of order.
     */
    private void applyOnFx(Runnable write) {
        if (dispatcher.isFxThread()) {
            write.run();
        } else {
            dispatcher.onFx(write);
        }
    }

    /**
     * Rebuilds {@link #inserts} and {@link #spatialNodePresent} from the
     * channel's current insert snapshot (FX thread). Maps each
     * {@link InsertSlot} per the {@link InsertSlotModel} contract:
     * {@code name = slot.getName()}, {@code bypassed = slot.isBypassed()},
     * {@code active = !bypassed} (every {@code InsertSlot} holds a processor —
     * the record's "empty slot" case cannot arise from a core chain).
     * Lock-free: {@code getInsertSlots()} returns the published snapshot and
     * {@code hasSpatialNode} reads a per-class cache.
     */
    private void rebuildInserts() {
        List<InsertSlot> slots = channel.getInsertSlots();
        List<InsertSlotModel> models = new ArrayList<>(slots.size());
        for (InsertSlot slot : slots) {
            boolean bypassed = slot.isBypassed();
            models.add(new InsertSlotModel(slot.getName(), !bypassed, bypassed));
        }
        inserts.setAll(models);
        spatialNodePresent.set(SpatialInserts.hasSpatialNode(channel));
    }

    /** Returns this channel's id (the addTrack pairing key with {@link TrackVM}). */
    public UUID channelId() {
        return channelId;
    }

    /**
     * Recomputes {@link #effectiveMute} from the channel's current mute/solo/
     * solo-safe state and the supplied project-wide solo state, applying the
     * audio engine's exact gate (mirrors {@code Mixer} processing:
     * {@code muted || (anySolo && !solo && !soloSafe)}). Package-private and
     * called only by {@link TrackChannelRegistry}, which invokes it on the FX
     * thread, so it sets the wrapper directly without re-marshalling.
     *
     * @param anySolo whether any channel in the project is currently soloed
     *                (the registry passes {@code false} for the master, which
     *                the engine never solo-gates)
     */
    void recomputeEffectiveMute(boolean anySolo) {
        effectiveMute.set(channel.isMuted()
                || (anySolo && !channel.isSolo() && !channel.isSoloSafe()));
    }

    // ── Read-only property views (the VM is the sole writer) ──────────────────

    /** The linear volume in [0,1]. Read-only; bound by the fader. */
    public ReadOnlyDoubleProperty volumeProperty() {
        return volume.getReadOnlyProperty();
    }

    /** Returns the current linear volume. */
    public double getVolume() {
        return volume.get();
    }

    /** The pan position in [−1,1]. Read-only; bound by the pan knob. */
    public ReadOnlyDoubleProperty panProperty() {
        return pan.getReadOnlyProperty();
    }

    /** Returns the current pan position. */
    public double getPan() {
        return pan.get();
    }

    /**
     * The channel's own muted flag (story 322). Read-only; bound by the
     * mute button of a strip whose channel has no track ({@code ChannelControlBinder}).
     * Distinct from {@link #effectiveMuteProperty()}, which also folds in
     * other channels' solo.
     */
    public ReadOnlyBooleanProperty mutedProperty() {
        return muted.getReadOnlyProperty();
    }

    /** Returns whether the channel itself is muted. */
    public boolean isMuted() {
        return muted.get();
    }

    /** The channel's own solo flag (story 322). Read-only; bound by a standalone strip's solo button. */
    public ReadOnlyBooleanProperty soloedProperty() {
        return soloed.getReadOnlyProperty();
    }

    /** Returns whether the channel itself is soloed. */
    public boolean isSoloed() {
        return soloed.get();
    }

    /**
     * The channel's insert chain as immutable {@link InsertSlotModel}s, in
     * chain order — the fact a strip's insert indicators render (story 322,
     * Audio Engine Wiring Design Book §5.6 "Strip insert indicator"). An
     * unmodifiable view: the VM is the sole writer, and it replaces the
     * contents wholesale ({@code setAll}) on every {@link ChangeKind#INSERTS}.
     */
    public ObservableList<InsertSlotModel> insertsProperty() {
        return insertsView;
    }

    /**
     * Whether the insert chain currently contains a spatial node (a processor
     * tagged {@code @ProcessorCapability(ProcessorCapabilities.SPATIAL)}) —
     * the fact the 3D-panner affordance is gated on (§5.6 "3D panner
     * button"). Derived on the same pass as {@link #insertsProperty()}.
     */
    public ReadOnlyBooleanProperty spatialNodePresentProperty() {
        return spatialNodePresent.getReadOnlyProperty();
    }

    /** Returns whether a spatial node is present in the insert chain. */
    public boolean isSpatialNodePresent() {
        return spatialNodePresent.get();
    }

    /**
     * The continuous meter level: this channel's post-fader <strong>peak in
     * dBFS</strong>, floored at {@value #METER_FLOOR_DB} (its initial value).
     * Read-only; bound by the strip meter. Updated once per frame via the
     * dispatcher drain from the engine's {@code CHANNEL_POST} tap once
     * {@link #bindMeter(Node)} has been called for a visible surface.
     */
    public ReadOnlyDoubleProperty meterLevelProperty() {
        return meterLevel.getReadOnlyProperty();
    }

    /** Returns the latest drained meter level (peak dBFS, floor {@value #METER_FLOOR_DB}). */
    public double getMeterLevel() {
        return meterLevel.get();
    }

    /**
     * The derived effective-mute flag — whether this channel is silenced by its
     * own mute or by another channel's solo. Read-only; recomputed by
     * {@link TrackChannelRegistry}.
     */
    public ReadOnlyBooleanProperty effectiveMuteProperty() {
        return effectiveMute.getReadOnlyProperty();
    }

    /** Returns whether the channel is currently effectively muted. */
    public boolean isEffectiveMute() {
        return effectiveMute.get();
    }

    /**
     * Unregisters the core change signal <em>and</em> closes the three
     * continuous channels so nothing leaks (story 291 AC: "{@code dispose()}
     * that unregisters and closes any subscription — no leaked listeners").
     * Closing the channels matters because they live in the long-lived,
     * app-scoped {@link FxDispatcher} and hold FX consumers (hence this VM);
     * leaving them open would leak the VM across every create/dispose cycle,
     * not just the upstream listener ({@code javafx-application-design}
     * §4/§11/§15). Idempotent — a second call is a no-op.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        // Story 318 — release the tap-bus subscription BEFORE closing the
        // channel it publishes into, so no pulse can publish into a closed
        // channel.
        unbindMeter();
        unregister.run();
        meterChannel.close();
        volumeChannel.close();
        panChannel.close();
    }
}
