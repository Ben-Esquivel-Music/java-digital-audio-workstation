package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.app.ui.metering.MeterSubscription;
import com.benesquivelmusic.daw.core.metering.MeterFrame;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.MixerChannel.ChangeKind;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The observable view-model mirror of a {@link MixerChannel} — the mixer-strip
 * half of story 291 (Control Synchronization Design Book §1.3, §3.1, §3.2,
 * §4.3, §4.5). {@code ChannelVM} projects the channel's continuous fader/pan
 * values, a per-frame meter level, and a <em>derived</em> effective-mute flag.
 *
 * <p>{@code ChannelVM} is the adapter that lets the JavaFX-free core be observed
 * without putting {@code javafx.beans.*} into {@code daw-core} (§2.5, §3.2, §9).
 * It registers the core's toolkit-neutral
 * {@link MixerChannel#addChangeListener(Consumer) change signal} and, on a
 * {@link ChangeKind#VOLUME} / {@link ChangeKind#PAN} tag, re-reads the slice and
 * republishes it as a read-only JavaFX {@code Property}. It is the
 * <strong>single writer</strong> of its properties (§2.4): the wrappers are
 * private and only the exposed {@code ReadOnly*Property} views are handed to
 * controls, so a control can bind but never write back (§1.4, §4.4).</p>
 *
 * <h2>The mute/solo split</h2>
 *
 * <p>A channel's own mute/solo is <em>not</em> projected as a discrete flag
 * here. The §1.3 fix makes {@code TrackVM} the single owner of the mute/solo
 * flag both surfaces bind to; what the mixer strip additionally needs is the
 * <em>effective</em> mute — whether the channel is silenced either by its own
 * mute or by another channel's solo. That value depends on project-wide solo
 * state, so {@link TrackChannelRegistry} (which sees every channel) owns its
 * recompute and pushes it in via {@link #recomputeEffectiveMute(boolean)}. The
 * {@link ChangeKind#MUTE} / {@link ChangeKind#SOLO} arms of
 * {@link #onCoreChange(ChangeKind)} are therefore no-ops here (the registry's
 * own listener drives the recompute) — listed only to keep the {@code switch}
 * exhaustive over {@link ChangeKind}.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>The core signal may fire on any thread; the discrete volume/pan writes are
 * marshalled onto the FX thread through the story-289
 * {@link FxDispatcher#onFx(Runnable)} (§4.5). The continuous {@link #meterLevel}
 * is published into the dispatcher's lock-free, single-reader primitive-{@code
 * double} buffer and delivered once per frame by the drain — never a per-tick
 * {@code runLater} — exactly as {@code TransportVM}'s playhead (§4.5, §5.2). The
 * producer side ({@code publish}) is wait-free and allocation-free, safe to call
 * from the {@code @RealTimeSafe} audio thread (§4.1, §4.6).</p>
 *
 * <h2>The meter producer (story 318)</h2>
 *
 * <p>{@link #meterLevel} now has a live producer: {@link #bindMeter(MeterFeed)}
 * subscribes this channel's post-fader {@link MeterTapPoint.ChannelPost} tap on
 * the engine's metering tap bus (Audio Engine Wiring Design Book §3.3, §4.3).
 * The render thread writes peak / RMS into a preallocated slot; the FX pulse
 * reads the newest coherent frame once per frame and publishes its peak, in
 * <strong>dBFS with a {@value #METER_FLOOR_DB} dB floor</strong>, through the
 * continuous channel — so the value a strip binds is exactly the level the
 * engine rendered, and it falls to the floor at stop (the feed's silent
 * frame). {@link #unbindMeter()} — and {@link #dispose()} — release the
 * subscription. Binding is optional: an unbound VM keeps its floor value, which
 * is what a pure-unit context or a project with no engine shows.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The constructor registers the core signal, opens the meter channel, and
 * seeds volume/pan. {@link #dispose()} unregisters the signal and closes the
 * meter channel so nothing leaks ({@code javafx-application-design} §3/§4/§11).
 * Idempotent and single-use.</p>
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

    /**
     * Whether the channel is currently silenced — either by its own mute or by
     * another channel's solo (the audio engine's exact gate; see
     * {@link #recomputeEffectiveMute(boolean)}). Derived: written only by
     * {@link TrackChannelRegistry} because it needs project-wide solo state.
     */
    private final ReadOnlyBooleanWrapper effectiveMute =
            new ReadOnlyBooleanWrapper(this, "effectiveMute");

    /**
     * Lock-free, single-reader primitive-{@code double} buffer feeding the
     * continuous {@link #meterLevel} (§4.5). The {@code double} specialization
     * so an audio-thread producer allocates no {@link Double} box. Closed in
     * {@link #dispose()}.
     */
    private final FxDispatcher.ContinuousDoubleChannel meterChannel;

    /** Removal token returned by {@link MixerChannel#addChangeListener(Consumer)}. */
    private final Runnable unregister;

    /** Story 318 — the live {@code CHANNEL_POST} subscription, or {@code null} when unbound. */
    private MeterSubscription meterSubscription;

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
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.channelId = channel.getId();

        this.meterChannel = dispatcher.openContinuousDouble(meterLevel::set);

        // Seed every property with the current state so a control binding shows
        // the correct value before the first signal arrives. effectiveMute is
        // seeded by the registry once it knows project-wide solo state.
        volume.set(channel.getVolume());
        pan.set(channel.getPan());
        // Story 318 — an unmetered channel reads as digital silence, not 0 dBFS
        // (which is full scale). Seeding the floor means a strip bound before
        // the first frame shows "no signal" rather than "clipping".
        meterLevel.set(METER_FLOOR_DB);

        this.unregister = channel.addChangeListener(this::onCoreChange);
    }

    /**
     * Subscribes this channel's post-fader {@code CHANNEL_POST} tap on
     * {@code feed} so {@link #meterLevelProperty()} carries the level the
     * engine actually renders (story 318).
     *
     * <p>Each delivered {@code MeterFrame} contributes one value: the loudest
     * lane's peak in dBFS, clamped to {@value #METER_FLOOR_DB} (the continuous
     * channel rejects {@code NaN}, and {@code -Infinity} — digital silence —
     * must never reach a binding). The subscription is always visible: a VM is
     * not a scene-graph node, and the surfaces that bind it decide their own
     * visibility. Binding twice replaces the previous subscription.</p>
     *
     * @param feed the FX-pulse meter drain; must not be {@code null}
     * @return a removal token equivalent to {@link #unbindMeter()}
     * @throws NullPointerException  if {@code feed} is {@code null}
     * @throws IllegalStateException if this VM is already disposed
     */
    public Runnable bindMeter(MeterFeed feed) {
        Objects.requireNonNull(feed, "feed must not be null");
        if (disposed) {
            throw new IllegalStateException("ChannelVM is disposed");
        }
        unbindMeter();
        meterSubscription = feed.subscribe(new MeterTapPoint.ChannelPost(channelId), this,
                () -> true, frame -> meterChannel.publish(peakDbFloored(frame)));
        return this::unbindMeter;
    }

    /**
     * Releases the meter subscription opened by {@link #bindMeter(MeterFeed)}.
     * Idempotent; a no-op when never bound. The last published level stays on
     * the property — the surface that unbinds decides what to show next.
     */
    public void unbindMeter() {
        if (meterSubscription != null) {
            meterSubscription.dispose();
            meterSubscription = null;
        }
    }

    /** {@code true} while a tap-bus subscription is feeding {@link #meterLevelProperty()}. */
    public boolean isMeterBound() {
        return meterSubscription != null;
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
     * Handles a core change signal. Runs on whatever thread mutated the channel;
     * routes the discrete volume/pan facts onto the FX thread (§4.5).
     * {@code MUTE} / {@code SOLO} are no-ops here — {@link #effectiveMute} is
     * owned by {@link TrackChannelRegistry}, which recomputes it project-wide
     * from its own listener — but are listed so the {@code switch} is exhaustive
     * over {@link ChangeKind}.
     */
    private void onCoreChange(ChangeKind kind) {
        switch (kind) {
            case VOLUME -> dispatcher.onFx(() -> volume.set(channel.getVolume()));
            case PAN -> dispatcher.onFx(() -> pan.set(channel.getPan()));
            // effectiveMute is recomputed project-wide by the registry.
            case MUTE, SOLO -> { }
        }
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
     * The continuous meter level: this channel's post-fader <strong>peak in
     * dBFS</strong>, floored at {@value #METER_FLOOR_DB} (its initial value).
     * Read-only; bound by the strip meter. Updated once per frame via the
     * dispatcher drain from the engine's {@code CHANNEL_POST} tap once
     * {@link #bindMeter(MeterFeed)} has been called.
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
     * Unregisters the core change signal <em>and</em> closes the continuous
     * meter channel so nothing leaks (story 291 AC: "{@code dispose()} that
     * unregisters and closes any subscription — no leaked listeners"). Closing
     * the channel matters because it lives in the long-lived, app-scoped
     * {@link FxDispatcher} and holds the FX consumer (hence this VM); leaving it
     * open would leak the VM across every create/dispose cycle, not just the
     * upstream listener ({@code javafx-application-design} §4/§11/§15).
     * Idempotent — a second call is a no-op.
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
    }
}
