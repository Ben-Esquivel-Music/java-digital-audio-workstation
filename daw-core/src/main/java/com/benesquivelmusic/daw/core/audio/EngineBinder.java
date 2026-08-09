package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single binding point between a live {@link DawProject} and the
 * {@link AudioEngine} (story 314, Audio Engine Wiring Design Book §4.1).
 *
 * <p>This is the <em>only</em> code allowed to call
 * {@link AudioEngine#setTransport}, {@link AudioEngine#setMixer}, and
 * {@link AudioEngine#setTracks} — a source-scan conformance test enforces
 * that. {@link #bind(DawProject)} hands the engine the project's live
 * {@code Transport} and {@code Mixer} references and an immutable snapshot
 * of the track list, refreshed on structural change only
 * ({@link DawProject.ChangeKind#TRACKS} — track add/remove/move/duplicate,
 * including undo paths). Per-block state (mute/volume/pan) is intentionally
 * <em>not</em> snapshotted: the render pipeline reads it live from each
 * {@code MixerChannel}; only structure is copied.</p>
 *
 * <h2>Threading contract</h2>
 * <p>{@link #bind(DawProject)} and {@link #unbind()} are lifecycle-thread
 * operations (the FX / lifecycle thread) — never call them from the
 * real-time audio callback. The engine setters they delegate to are plain
 * volatile stores, which is what publishes the new graph to the RT
 * callback's next {@code processBlock}. The tracks-refresh listener runs on
 * whichever thread performed the project mutation (the UI thread in
 * production); allocating the snapshot copy there is fine.</p>
 *
 * <h2>Binding epoch</h2>
 * <p>The binder owns a binding epoch that increments on every
 * {@link #bind(DawProject)} (book §3.1). Consumers that captured references
 * for epoch N (VMs, ruler bindings, tap subscriptions) are disposed before
 * epoch N+1 binds (book §6.2); {@link #unbind()} does not advance the
 * epoch.</p>
 */
public final class EngineBinder {

    private final AudioEngine engine;
    private final AtomicLong epoch = new AtomicLong();

    private volatile DawProject boundProject;
    /**
     * Unregister token remembered from the previous bind's
     * {@link DawProject#addChangeListener} — the detach must run against
     * the token we attached, never a re-read of the current project.
     */
    private Runnable trackListenerUnregister;

    /**
     * Creates a binder for the given engine.
     *
     * @param engine the engine this binder publishes project graphs to
     */
    public EngineBinder(AudioEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    /**
     * Binds the given project to the engine: detaches the previous
     * project's tracks listener, then publishes the new graph under the
     * <em>gate-off → swap → gate-on</em> rule that holds on every path
     * (mirroring {@link #unbind()}'s transport-first rule): the transport is
     * nulled first — the transport is the {@code playbackActive} gate, so a
     * rebind while the previous project is still playing can never expose a
     * half-swapped (new tracks, old mixer/transport) graph to the RT
     * callback — then the tracks snapshot and mixer land, and the project's
     * live transport is set <em>last</em> so the gate flips on only after
     * the whole graph is in place. Also registers the structural-change
     * refresh listener, {@linkplain #refreshPerformanceMonitor() refreshes}
     * the {@link PerformanceMonitor}, and increments the binding epoch.
     *
     * @param project the project to bind; must not be {@code null}
     */
    public void bind(DawProject project) {
        Objects.requireNonNull(project, "project must not be null");
        detachTrackListener();

        engine.setTransport(null);
        engine.setTracks(List.copyOf(project.getTracks()));
        engine.setMixer(project.getMixer());
        engine.setTransport(project.getTransport());

        trackListenerUnregister = project.addChangeListener(kind -> {
            if (kind == DawProject.ChangeKind.TRACKS) {
                engine.setTracks(List.copyOf(project.getTracks()));
            }
        });

        refreshPerformanceMonitor();

        boundProject = project;
        epoch.incrementAndGet();
    }

    /**
     * Replaces the engine's {@link PerformanceMonitor} when it is absent or
     * was built for a different {@link AudioFormat} than the engine's
     * current one — the monitor's per-block budget is fixed at
     * construction, so a stale monitor misreports CPU load and fires false
     * underrun warnings after a sample-rate / buffer-size change. A
     * same-format call keeps the existing instance, so attached warning
     * listeners survive rebinds. Called from {@link #bind(DawProject)} and
     * by the post-reconfigure callback after every engine format apply.
     */
    public void refreshPerformanceMonitor() {
        PerformanceMonitor monitor = engine.getPerformanceMonitor();
        AudioFormat format = engine.getFormat();
        if (monitor == null || !format.equals(monitor.getFormat())) {
            engine.setPerformanceMonitor(new PerformanceMonitor(format));
        }
    }

    /**
     * Unbinds the current project: detaches the remembered tracks listener,
     * then nulls the transport <em>first</em> (killing
     * {@code playbackActive} immediately), then the mixer and tracks.
     * Idempotent; does not increment the epoch.
     */
    public void unbind() {
        detachTrackListener();
        engine.setTransport(null);
        engine.setMixer(null);
        engine.setTracks(null);
        boundProject = null;
    }

    /**
     * Returns the current binding epoch. Increments on every
     * {@link #bind(DawProject)}; readable from any thread.
     *
     * @return the epoch (0 before the first bind)
     */
    public long epoch() {
        return epoch.get();
    }

    /**
     * Returns the currently bound project, if any.
     *
     * @return the bound project, or empty when unbound
     */
    public Optional<DawProject> boundProject() {
        return Optional.ofNullable(boundProject);
    }

    private void detachTrackListener() {
        if (trackListenerUnregister != null) {
            trackListenerUnregister.run();
            trackListenerUnregister = null;
        }
    }
}
