package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.Track.ChangeKind;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The observable view-model mirror of a {@link Track} — the arrangement-lane
 * half of story 291 (Control Synchronization Design Book §1.3, §3.1, §3.2,
 * §4.3). {@code TrackVM} projects the track's name and its three transport
 * flags (mute, solo, arm) — the slices an arrangement lane <em>and</em> a mixer
 * strip both display.
 *
 * <p>{@code TrackVM} is the adapter that lets the JavaFX-free core be observed
 * without putting {@code javafx.beans.*} into {@code daw-core} (§2.5, §3.2, §9).
 * It registers the core's toolkit-neutral
 * {@link Track#addChangeListener(Consumer) change signal} and, on each
 * {@link ChangeKind}, re-reads the affected slice and republishes it as a
 * read-only JavaFX {@code Property}. It is the <strong>single writer</strong> of
 * its properties (§2.4): the wrappers are private and only the exposed
 * {@code ReadOnly*Property} views are handed to controls, so a control can bind
 * but never write back — the feedback-guard smell of §1.4 cannot arise.</p>
 *
 * <h2>The §1.3 "one flag, both surfaces" join</h2>
 *
 * <p>Historically the codebase carried two unsynchronised mute/solo flags:
 * {@code Track.muted/solo} (the arrangement lane and persistence) and
 * {@code MixerChannel.muted/solo} (what the audio engine reads). Story 291
 * unifies them by making the mute/solo <em>command</em>
 * ({@code CoreTrackIntentHandler}) mutate both in lock-step, so this single
 * {@code TrackVM} flag is the one truth both the lane button and the mixer strip
 * bind to ({@code TrackControlBinder.bindChannelStrip}). {@code TrackVM} mirrors
 * the {@code Track} (the authority for the lane and persistence); the paired
 * {@code MixerChannel} stays coherent because the command writes both.</p>
 *
 * <h2>Scope</h2>
 *
 * <p>Only name + the three flags are projected. {@code COLOR} is not a
 * {@link ChangeKind} (the core fires no signal for it) and {@code Track} has no
 * height field, so neither is exposed — the lanes of this story do not need
 * them. Volume and pan live on the paired {@code MixerChannel} and are projected
 * by {@link ChannelVM}; the {@link ChangeKind#VOLUME} / {@link ChangeKind#PAN}
 * arms of {@link #onCoreChange(ChangeKind)} are therefore deliberate no-ops that
 * keep the {@code switch} exhaustive over the enum.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>The core signal may fire on any thread; every property write is marshalled
 * onto the FX thread through the story-289 {@link FxDispatcher#onFx(Runnable)}
 * (§4.5). {@code TrackVM} carries no continuous (per-frame) value, so unlike
 * {@code TransportVM} it opens no {@link FxDispatcher.ContinuousDoubleChannel}.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The constructor registers the core signal and seeds every property with the
 * current track state. {@link #dispose()} unregisters the signal so no listener
 * leaks ({@code javafx-application-design} §3/§4/§11). The instance is
 * single-use; after {@code dispose()} it receives no further signals.</p>
 */
public final class TrackVM {

    private final Track track;
    private final FxDispatcher dispatcher;

    /**
     * The track id, seeded once at construction from the track's UUID-String id
     * so a lookup never has to re-parse it. Holds for the addTrack invariant
     * pairing in {@link TrackChannelRegistry}.
     */
    private final UUID trackId;

    private final ReadOnlyStringWrapper name =
            new ReadOnlyStringWrapper(this, "name");
    private final ReadOnlyBooleanWrapper muted =
            new ReadOnlyBooleanWrapper(this, "muted");
    private final ReadOnlyBooleanWrapper soloed =
            new ReadOnlyBooleanWrapper(this, "soloed");
    private final ReadOnlyBooleanWrapper armed =
            new ReadOnlyBooleanWrapper(this, "armed");

    /** Removal token returned by {@link Track#addChangeListener(Consumer)}. */
    private final Runnable unregister;

    private boolean disposed;

    /**
     * Creates a view-model bound to {@code track}, marshalling all property
     * writes through {@code dispatcher}.
     *
     * @param track      the authoritative track to mirror; must not be {@code null}
     * @param dispatcher the marshalling seam (story 289); must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public TrackVM(Track track, FxDispatcher dispatcher) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.trackId = UUID.fromString(track.getId());

        // Seed every property with the current state so a control binding shows
        // the correct value before the first signal arrives.
        name.set(track.getName());
        muted.set(track.isMuted());
        soloed.set(track.isSolo());
        armed.set(track.isArmed());

        this.unregister = track.addChangeListener(this::onCoreChange);
    }

    /**
     * Handles a core change signal. Runs on whatever thread mutated the track;
     * routes each affected flag onto the FX thread (§4.5). {@code VOLUME} and
     * {@code PAN} are no-ops here — those slices belong to {@link ChannelVM} —
     * but are listed so the {@code switch} is exhaustive over {@link ChangeKind}.
     */
    private void onCoreChange(ChangeKind kind) {
        switch (kind) {
            case NAME -> dispatcher.onFx(() -> name.set(track.getName()));
            case MUTE -> dispatcher.onFx(() -> muted.set(track.isMuted()));
            case SOLO -> dispatcher.onFx(() -> soloed.set(track.isSolo()));
            case ARM -> dispatcher.onFx(() -> armed.set(track.isArmed()));
            // Volume/pan are projected by ChannelVM, not TrackVM.
            case VOLUME, PAN -> { }
        }
    }

    /** Returns this track's id (the addTrack pairing key with {@link ChannelVM}). */
    public UUID trackId() {
        return trackId;
    }

    // ── Read-only property views (the VM is the sole writer) ──────────────────

    /** The display name. Read-only; bound by the lane header and mixer strip. */
    public ReadOnlyStringProperty nameProperty() {
        return name.getReadOnlyProperty();
    }

    /** Returns the current display name. */
    public String getName() {
        return name.get();
    }

    /** The muted flag. Read-only; bound by the lane mute button and mixer strip. */
    public ReadOnlyBooleanProperty mutedProperty() {
        return muted.getReadOnlyProperty();
    }

    /** Returns whether the track is muted. */
    public boolean isMuted() {
        return muted.get();
    }

    /** The soloed flag. Read-only; bound by the lane solo button and mixer strip. */
    public ReadOnlyBooleanProperty soloedProperty() {
        return soloed.getReadOnlyProperty();
    }

    /** Returns whether the track is soloed. */
    public boolean isSoloed() {
        return soloed.get();
    }

    /** The armed (record-ready) flag. Read-only; bound by the lane arm button and mixer strip. */
    public ReadOnlyBooleanProperty armedProperty() {
        return armed.getReadOnlyProperty();
    }

    /** Returns whether the track is armed for recording. */
    public boolean isArmed() {
        return armed.get();
    }

    /**
     * Unregisters the core change signal so nothing leaks (story 291 AC:
     * "{@code dispose()} that unregisters … no leaked listeners"). Idempotent —
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
