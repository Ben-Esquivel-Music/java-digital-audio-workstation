package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The small view-model registry that builds and owns a {@link TrackVM} per
 * project track and a {@link ChannelVM} per mixer channel, pairs them by id,
 * and coordinates the one cross-channel concern a single VM cannot compute on
 * its own: each channel's {@linkplain ChannelVM#effectiveMuteProperty()
 * effective mute} (story 291; Control Synchronization Design Book §1.3, §3.2,
 * §4.5).
 *
 * <h2>Pairing (the addTrack invariant)</h2>
 *
 * <p>A {@code Track} added via {@link DawProject#addTrack(Track)} shares its id
 * with its lazily-created {@code MixerChannel}
 * ({@code channel.getId().equals(UUID.fromString(track.getId()))}). The registry
 * <em>derives</em> the pairing from the project's authoritative ids rather than
 * assuming it: a {@link ChannelVM} is paired with the {@link TrackVM} whose
 * {@link TrackVM#trackId()} equals the channel's {@link ChannelVM#channelId()}.
 * A channel with no matching track VM — an aux/return/cue/VCA/master channel,
 * whose id is a fresh random UUID (the carve-out) — is <strong>standalone</strong>
 * and has no track peer. {@link #peerTrackVm(ChannelVM)} returns
 * {@link Optional#empty()} for it.</p>
 *
 * <h2>Effective-mute orchestration</h2>
 *
 * <p>A channel is silenced when it is muted or when another channel is soloed
 * and it is neither solo nor solo-safe. That gate depends on project-wide solo
 * state, so it cannot live in a single {@link ChannelVM}. The registry registers
 * its <em>own</em> listener on every track {@code MixerChannel} <em>and</em> on
 * every return bus (separate from the {@code ChannelVM}'s) and, on any
 * {@code MUTE} or {@code SOLO} signal, marshals
 * {@link #recomputeAllEffectiveMutes()} onto the FX thread. The recompute reads
 * {@code anySolo} from {@link Mixer#isAnySolo()} — the engine's own predicate,
 * which counts a soloed return bus as well as a soloed track — so the displayed
 * effective-mute never diverges from what the audio engine actually silences.
 * Return buses are observed (though they have no {@code ChannelVM}) precisely
 * because soloing one silences non-solo-safe tracks. The constructor seeds the
 * values immediately so a binding is correct before the first signal.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The constructor builds and seeds everything. {@link #dispose()} removes the
 * registry's own listeners and disposes every {@code TrackVM}/{@code ChannelVM}
 * (each of which closes its meter channel and unregisters its signal), so
 * nothing leaks. Idempotent and single-use.</p>
 */
public final class TrackChannelRegistry {

    private final FxDispatcher dispatcher;

    /** Track VMs keyed by {@link TrackVM#trackId()}, in project order. */
    private final Map<UUID, TrackVM> trackVms = new LinkedHashMap<>();

    /** Channel VMs keyed by {@link ChannelVM#channelId()}, in mixer order. */
    private final Map<UUID, ChannelVM> channelVms = new LinkedHashMap<>();

    /** Removal tokens for the registry's own per-channel effective-mute listeners. */
    private final List<Runnable> channelListenerTokens = new ArrayList<>();

    /** The mixer, queried via {@link Mixer#isAnySolo()} for the project-wide solo picture. */
    private final Mixer mixer;

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
        Objects.requireNonNull(project, "project must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.mixer = project.getMixer();

        for (Track track : project.getTracks()) {
            TrackVM vm = new TrackVM(track, dispatcher);
            trackVms.put(vm.trackId(), vm);
        }
        for (MixerChannel channel : mixer.getChannels()) {
            ChannelVM vm = new ChannelVM(channel, dispatcher);
            channelVms.put(vm.channelId(), vm);
            // The registry's OWN listener (distinct from the ChannelVM's): a
            // MUTE/SOLO anywhere changes the project-wide solo picture, so every
            // channel's effective mute must be recomputed, not just this one's.
            channelListenerTokens.add(channel.addChangeListener(this::onChannelMuteOrSolo));
        }
        // Return buses have no ChannelVM, but soloing one silences non-solo-safe
        // tracks (Mixer.isAnySolo() counts return-bus solo), so the registry must
        // also observe them to keep every track channel's effective-mute correct.
        for (MixerChannel returnBus : mixer.getReturnBuses()) {
            channelListenerTokens.add(returnBus.addChangeListener(this::onChannelMuteOrSolo));
        }

        // Seed effective-mute for every channel from the current solo picture.
        recomputeAllEffectiveMutes();
    }

    /**
     * Registry-owned reaction to a channel mute/solo signal. Fires on whatever
     * thread mutated the channel; a {@code MUTE} or {@code SOLO} changes the
     * project-wide solo state, so the whole effective-mute pass is marshalled
     * onto the FX thread. {@code VOLUME}/{@code PAN} are irrelevant to mute and
     * ignored.
     */
    private void onChannelMuteOrSolo(MixerChannel.ChangeKind kind) {
        switch (kind) {
            case MUTE, SOLO -> scheduleEffectiveMuteRecompute();
            case VOLUME, PAN -> { }
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
     * what the engine actually silences.
     */
    void recomputeAllEffectiveMutes() {
        boolean anySolo = mixer.isAnySolo();
        for (ChannelVM vm : channelVms.values()) {
            vm.recomputeEffectiveMute(anySolo);
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
     * Returns the {@link TrackVM} paired with {@code channelVm} via the addTrack
     * id invariant, or {@link Optional#empty()} when the channel is standalone
     * (aux/return/cue/VCA/master — the carve-out, whose id matches no track).
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

    /** Returns an immutable snapshot of the registered channel VMs, in mixer order. */
    public List<ChannelVM> channelVms() {
        return List.copyOf(channelVms.values());
    }

    /**
     * Removes the registry's own per-channel listeners and disposes every track
     * and channel VM (each closes its meter channel and unregisters its signal),
     * so nothing leaks. Idempotent — a second call is a no-op.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        for (Runnable token : channelListenerTokens) {
            token.run();
        }
        channelListenerTokens.clear();
        for (TrackVM vm : trackVms.values()) {
            vm.dispose();
        }
        for (ChannelVM vm : channelVms.values()) {
            vm.dispose();
        }
        trackVms.clear();
        channelVms.clear();
    }
}
