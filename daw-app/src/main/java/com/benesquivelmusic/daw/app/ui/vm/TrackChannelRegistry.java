package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The view-model registry that builds and owns a {@link TrackVM} per project
 * track and a {@link ChannelVM} per mixer channel — track channels, return
 * buses and the master — pairs track and channel VMs by id, keeps the set
 * reconciled with the live project, and coordinates the one cross-channel
 * concern a single VM cannot compute on its own: each channel's
 * {@linkplain ChannelVM#effectiveMuteProperty() effective mute} (story 291;
 * Control Synchronization Design Book §1.3, §3.2, §4.5; story 322, Audio
 * Engine Wiring Design Book §5.6).
 *
 * <h2>Pairing (the addTrack invariant)</h2>
 *
 * <p>A {@code Track} added via {@link DawProject#addTrack(Track)} shares its id
 * with its lazily-created {@code MixerChannel}
 * ({@code channel.getId().equals(UUID.fromString(track.getId()))}). The registry
 * <em>derives</em> the pairing from the project's authoritative ids rather than
 * assuming it: a {@link ChannelVM} is paired with the {@link TrackVM} whose
 * {@link TrackVM#trackId()} equals the channel's {@link ChannelVM#channelId()}.
 * A channel with no matching track VM — a return bus, the master, or any
 * channel added straight to the mixer, whose id is a fresh random UUID (the
 * carve-out) — is <strong>standalone</strong> and has no track peer.
 * {@link #peerTrackVm(ChannelVM)} returns {@link Optional#empty()} for it.
 * (A track whose id is not a UUID — a reflective test fixture — gets no
 * {@code TrackVM}; its channel, if any, is registered as standalone.)</p>
 *
 * <h2>Live reconciliation (story 322)</h2>
 *
 * <p>The registry is constructed once per project generation in production
 * ({@code MainController.rebuildTrackControlWiring()}) and then <em>stays
 * live</em>: {@link #reconcile()} re-derives the desired VM set from the
 * project's tracks, the mixer's channels, its return buses and the master,
 * creating VMs for new entities and disposing those whose entity is gone. It
 * runs from the constructor, on every {@link DawProject.ChangeKind#TRACKS}
 * signal (inline when the signal arrives on the FX thread, else marshalled
 * through {@link FxDispatcher#onFx(Runnable)} and coalesced), and on demand —
 * {@code MixerView.refresh()} calls it because return-bus add/remove has no
 * core signal. Every pass re-reads the authority, so the last pass after the
 * last change always wins. If a pass observes a mutation in flight
 * ({@link ConcurrentModificationException} from a live list view), it simply
 * yields: that mutation's own {@code TRACKS} signal runs a fresh pass.</p>
 *
 * <h2>Effective-mute orchestration</h2>
 *
 * <p>A channel is silenced when it is muted or when another channel is soloed
 * and it is neither solo nor solo-safe. That gate depends on project-wide solo
 * state, so it cannot live in a single {@link ChannelVM}. The registry registers
 * its <em>own</em> listener on every channel it mirrors (separate from the
 * {@code ChannelVM}'s) and, on any {@code MUTE} or {@code SOLO} signal, marshals
 * {@link #recomputeAllEffectiveMutes()} onto the FX thread. The recompute reads
 * {@code anySolo} from {@link Mixer#isAnySolo()} — the engine's own predicate,
 * which counts a soloed return bus as well as a soloed track — so the displayed
 * effective-mute never diverges from what the audio engine actually silences.
 * The engine applies the same gate to return buses; the master is gated by its
 * own mute only, so {@link #masterVm()} is recomputed with {@code anySolo =
 * false}.</p>
 *
 * <h2>Meter binding (story 318)</h2>
 *
 * <p>The {@linkplain #TrackChannelRegistry(DawProject, FxDispatcher, MeterFeed)
 * three-argument constructor} supplies every {@link ChannelVM} with a feed,
 * without creating any subscriptions. Each consuming surface calls
 * {@link ChannelVM#bindMeter(javafx.scene.Node)} to supply its visibility and
 * owns the returned removal token. Every VM this registry creates goes through
 * {@link #registerChannelVm(MixerChannel)}, so a channel registered by a later
 * reconcile is configured the same way. {@link #dispose()} unbinds them all.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The constructor builds and seeds everything and subscribes the project
 * signal. {@link #dispose()} unsubscribes the project, removes the registry's
 * own channel listeners, and disposes every {@code TrackVM}/{@code ChannelVM}
 * (each of which closes its channels and unregisters its signal), so nothing
 * leaks. Idempotent and single-use.</p>
 */
public final class TrackChannelRegistry {

    private final DawProject project;
    private final FxDispatcher dispatcher;

    /** Track VMs keyed by {@link TrackVM#trackId()}, in project order. */
    private final Map<UUID, TrackVM> trackVms = new LinkedHashMap<>();

    /**
     * Channel VMs keyed by {@link ChannelVM#channelId()}: track channels in
     * mixer order, then return buses, then the master.
     */
    private final Map<UUID, ChannelVM> channelVms = new LinkedHashMap<>();

    /** Removal tokens for the registry's own per-channel effective-mute listeners, by channel id. */
    private final Map<UUID, Runnable> channelListenerTokens = new LinkedHashMap<>();

    /**
     * Story 318 — the tap-bus drain every {@link ChannelVM} this registry
     * creates can bind a surface to, or {@code null} when the registry was built without
     * one (the two-argument constructor, and every pure-unit context).
     */
    private final MeterFeed meterFeed;

    /** The mixer, queried via {@link Mixer#isAnySolo()} for the project-wide solo picture. */
    private final Mixer mixer;

    /** Removal token for the registry's {@link DawProject.ChangeKind#TRACKS} subscription. */
    private final Runnable projectUnregister;

    /**
     * Coalescing guard for the project-wide effective-mute recompute. A burst of
     * mute/solo signals (e.g. "solo all" over N channels) would otherwise post N
     * separate {@code onFx(recompute)} jobs, each an O(channels) pass — O(N²).
     * While one pass is already queued this stays {@code true} and further signals
     * are dropped; the queued pass clears it <em>before</em> reading state, so a
     * signal arriving mid-recompute still queues a fresh pass (at-least-once after
     * the last change).
     */
    private final AtomicBoolean recomputePending = new AtomicBoolean(false);

    /** Same coalescing discipline as {@link #recomputePending}, for off-FX reconcile passes. */
    private final AtomicBoolean reconcilePending = new AtomicBoolean(false);

    private boolean disposed;

    /**
     * Builds the registry over {@code project}, marshalling effective-mute
     * recomputes through {@code dispatcher}.
     *
     * @param project    the project whose tracks and channels are mirrored; must not be {@code null}
     * @param dispatcher the marshalling seam (story 289); must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public TrackChannelRegistry(DawProject project, FxDispatcher dispatcher) {
        this(project, dispatcher, null);
    }

    /**
     * Builds the registry over {@code project} and supplies {@code meterFeed}
     * to every {@link ChannelVM}. Meter demand begins only when an actual
     * surface binds itself to the VM and is visible.
     *
     * @param project    the project whose tracks and channels are mirrored; must not be {@code null}
     * @param dispatcher the marshalling seam (story 289); must not be {@code null}
     * @param meterFeed  the FX-pulse meter drain, or {@code null} to leave every
     *                   meter at its floor (the two-argument behaviour)
     * @throws NullPointerException if {@code project} or {@code dispatcher} is {@code null}
     */
    public TrackChannelRegistry(DawProject project, FxDispatcher dispatcher, MeterFeed meterFeed) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.mixer = project.getMixer();
        this.meterFeed = meterFeed;

        // Seed every VM (and every effective-mute) from the current project.
        reconcile();
        this.projectUnregister = project.addChangeListener(this::onProjectChange);
    }

    /**
     * Re-derives the VM set from the live project: a {@link TrackVM} per track
     * (UUID ids only), a {@link ChannelVM} per mixer channel, per return bus and
     * for the master. VMs for entities that are gone are disposed; new entities
     * get fresh, fully-wired VMs; surviving VMs are kept (their bindings stay
     * valid). Ends with a project-wide effective-mute recompute so new VMs are
     * seeded. FX thread only (the constructor may call it from the constructing
     * thread). Safe to call when nothing changed — a no-op then.
     *
     * <p>Public because return-bus add/remove has no core signal:
     * {@code MixerView.refresh()} calls this before rebuilding strips (story
     * 322).</p>
     */
    public void reconcile() {
        if (disposed) {
            return;
        }
        List<Track> tracks;
        List<MixerChannel> desiredChannels;
        try {
            tracks = List.copyOf(project.getTracks());
            List<MixerChannel> channels = mixer.getChannels();
            List<MixerChannel> returnBuses = mixer.getReturnBuses();
            desiredChannels = new ArrayList<>(channels.size() + returnBuses.size() + 1);
            desiredChannels.addAll(channels);
            desiredChannels.addAll(returnBuses);
            desiredChannels.add(mixer.getMasterChannel());
        } catch (ConcurrentModificationException mutationInFlight) {
            // A mutator is mid-add/remove on another thread; its own TRACKS
            // signal (fired after the write) schedules the pass that sees the
            // settled list. Nothing was changed here.
            return;
        }
        reconcileTracks(tracks);
        reconcileChannels(desiredChannels);
        recomputeAllEffectiveMutes();
    }

    private void reconcileTracks(List<Track> tracks) {
        Map<UUID, TrackVM> next = new LinkedHashMap<>();
        for (Track track : tracks) {
            UUID id = parseUuid(track.getId());
            if (id == null) {
                continue; // non-UUID fixture id — no TrackVM (see class Javadoc)
            }
            TrackVM vm = trackVms.remove(id);
            next.put(id, vm != null ? vm : new TrackVM(track, dispatcher));
        }
        for (TrackVM removed : trackVms.values()) {
            removed.dispose();
        }
        trackVms.clear();
        trackVms.putAll(next);
    }

    private void reconcileChannels(List<MixerChannel> desired) {
        Map<UUID, ChannelVM> next = new LinkedHashMap<>();
        for (MixerChannel channel : desired) {
            UUID id = channel.getId();
            ChannelVM vm = channelVms.remove(id);
            next.put(id, vm != null ? vm : registerChannelVm(channel));
        }
        for (ChannelVM removed : channelVms.values()) {
            Runnable token = channelListenerTokens.remove(removed.channelId());
            if (token != null) {
                token.run();
            }
            removed.dispose();
        }
        channelVms.clear();
        channelVms.putAll(next);
    }

    private static UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * Creates and supplies the optional meter feed to one channel's view-model
     * without acquiring a subscription, and registers the registry's own
     * effective-mute listener on the channel. Every {@link ChannelVM} this
     * registry owns is created here, so a channel registered by a later
     * reconcile is wired exactly like one present at construction.
     *
     * @param channel the mixer channel to mirror
     * @return the new VM (the caller files it)
     */
    private ChannelVM registerChannelVm(MixerChannel channel) {
        ChannelVM vm = new ChannelVM(channel, dispatcher, meterFeed);
        // The registry's OWN listener (distinct from the ChannelVM's): a
        // MUTE/SOLO anywhere changes the project-wide solo picture, so every
        // channel's effective mute must be recomputed, not just this one's.
        channelListenerTokens.put(vm.channelId(), channel.addChangeListener(this::onChannelMuteOrSolo));
        return vm;
    }

    /**
     * Registry-owned reaction to a project signal. {@code TRACKS} (add /
     * remove / move / duplicate) re-derives the VM set — inline when already
     * on the FX thread, else marshalled and coalesced. {@code NAME}/{@code DIRTY}
     * are {@code ProjectVM}'s facts and ignored here.
     */
    private void onProjectChange(DawProject.ChangeKind kind) {
        switch (kind) {
            case TRACKS -> scheduleReconcile();
            case NAME, DIRTY -> { }
        }
    }

    /**
     * Inline on the FX thread — {@link FxDispatcher#isFxThread()}, the
     * lock-free field compare every VM uses, never
     * {@code Platform.isFxApplicationThread()} (see {@code ChannelVM},
     * "Threading") — else one coalesced {@code onFx} pass.
     */
    private void scheduleReconcile() {
        if (dispatcher.isFxThread()) {
            reconcile();
            return;
        }
        if (reconcilePending.compareAndSet(false, true)) {
            dispatcher.onFx(() -> {
                reconcilePending.set(false);
                reconcile();
            });
        }
    }

    /**
     * Registry-owned reaction to a channel mute/solo signal. Fires on whatever
     * thread mutated the channel; a {@code MUTE} or {@code SOLO} changes the
     * project-wide solo state, so the whole effective-mute pass is marshalled
     * onto the FX thread. {@code VOLUME}/{@code PAN}/{@code INSERTS} are
     * irrelevant to mute and ignored.
     */
    private void onChannelMuteOrSolo(MixerChannel.ChangeKind kind) {
        switch (kind) {
            case MUTE, SOLO -> scheduleEffectiveMuteRecompute();
            case VOLUME, PAN, INSERTS -> { }
        }
    }

    /**
     * Queues a single project-wide effective-mute recompute on the FX thread,
     * coalescing a burst of mute/solo signals into one pass (see
     * {@link #recomputePending}). Lock-free: the first signal CASes the guard and
     * posts the job; concurrent signals see it already pending and skip. The job
     * clears the guard before recomputing, so the next signal queues a fresh pass.
     */
    private void scheduleEffectiveMuteRecompute() {
        if (recomputePending.compareAndSet(false, true)) {
            dispatcher.onFx(() -> {
                recomputePending.set(false);
                recomputeAllEffectiveMutes();
            });
        }
    }

    /**
     * Recomputes {@link ChannelVM#effectiveMuteProperty()} for every channel from
     * a single {@code anySolo} reading. Runs on the FX thread (the constructor
     * seeds it inline at construction; thereafter {@link #onChannelMuteOrSolo}
     * marshals it). {@code anySolo} is read from {@link Mixer#isAnySolo()} — the
     * engine's own predicate, which counts both a soloed track channel and a
     * soloed return bus — so the displayed effective-mute never diverges from
     * what the engine actually silences. The master is never solo-gated by the
     * engine (its mute alone silences it), so it is recomputed with
     * {@code anySolo = false}.
     */
    void recomputeAllEffectiveMutes() {
        boolean anySolo = mixer.isAnySolo();
        UUID masterId = mixer.getMasterChannel().getId();
        for (ChannelVM vm : channelVms.values()) {
            vm.recomputeEffectiveMute(anySolo && !vm.channelId().equals(masterId));
        }
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    /**
     * Returns the {@link TrackVM} for the given track id, or {@code null} if no
     * track with that id is registered.
     *
     * @param trackId the track id
     * @return the track VM, or {@code null}
     */
    public TrackVM trackVm(UUID trackId) {
        return trackVms.get(trackId);
    }

    /**
     * Returns the {@link ChannelVM} for the given channel id, or {@code null} if
     * no channel with that id is registered.
     *
     * @param channelId the channel id
     * @return the channel VM, or {@code null}
     */
    public ChannelVM channelVm(UUID channelId) {
        return channelVms.get(channelId);
    }

    /**
     * Returns the master channel's {@link ChannelVM} (story 322 — the master
     * strip binds through {@code ChannelControlBinder}).
     *
     * @return the master VM; never {@code null} while the registry is undisposed
     * @throws IllegalStateException if the registry is disposed
     */
    public ChannelVM masterVm() {
        ChannelVM vm = channelVms.get(mixer.getMasterChannel().getId());
        if (vm == null) {
            throw new IllegalStateException("registry is disposed");
        }
        return vm;
    }

    /**
     * Returns the {@link TrackVM} paired with {@code channelVm} via the addTrack
     * id invariant, or {@link Optional#empty()} when the channel is standalone
     * (return/master/aux/cue/VCA — the carve-out, whose id matches no track).
     *
     * @param channelVm the channel VM; must not be {@code null}
     * @return the paired track VM, or empty if standalone
     */
    public Optional<TrackVM> peerTrackVm(ChannelVM channelVm) {
        Objects.requireNonNull(channelVm, "channelVm must not be null");
        return Optional.ofNullable(trackVms.get(channelVm.channelId()));
    }

    /**
     * Returns the {@link ChannelVM} paired with {@code trackVm} via the addTrack
     * id invariant, or {@link Optional#empty()} when no channel shares the
     * track's id (a track whose channel has not been created).
     *
     * @param trackVm the track VM; must not be {@code null}
     * @return the paired channel VM, or empty
     */
    public Optional<ChannelVM> peerChannelVm(TrackVM trackVm) {
        Objects.requireNonNull(trackVm, "trackVm must not be null");
        return Optional.ofNullable(channelVms.get(trackVm.trackId()));
    }

    /** Returns an immutable snapshot of the registered track VMs, in project order. */
    public List<TrackVM> trackVms() {
        return List.copyOf(trackVms.values());
    }

    /**
     * Returns an immutable snapshot of the registered channel VMs: track
     * channels in mixer order, then return buses, then the master.
     */
    public List<ChannelVM> channelVms() {
        return List.copyOf(channelVms.values());
    }

    /**
     * Unsubscribes the project signal, removes the registry's own per-channel
     * listeners, unbinds every meter subscription (story 318) and disposes every
     * track and channel VM (each closes its channels and unregisters its
     * signal), so nothing leaks. Idempotent — a second call is a no-op.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        projectUnregister.run();
        for (Runnable token : channelListenerTokens.values()) {
            token.run();
        }
        channelListenerTokens.clear();
        for (TrackVM vm : trackVms.values()) {
            vm.dispose();
        }
        for (ChannelVM vm : channelVms.values()) {
            // ChannelVM.dispose() unbinds its meter subscription first; the
            // explicit unbind here is not needed and would be redundant.
            vm.dispose();
        }
        trackVms.clear();
        channelVms.clear();
    }
}
