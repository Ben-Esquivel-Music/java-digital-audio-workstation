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
 * {@link AudioEngine#setGraph} and {@link AudioEngine#setTracks} — a
 * source-scan conformance test enforces that. {@link #bind(DawProject)}
 * hands the engine the project's live {@code Transport} and {@code Mixer}
 * references and an immutable snapshot of the track list, refreshed on
 * structural change only ({@link DawProject.ChangeKind#TRACKS} — track
 * add/remove/move/duplicate, including undo paths). Per-block state
 * (mute/volume/pan) is intentionally <em>not</em> snapshotted: the render
 * pipeline reads it live from each {@code MixerChannel}; only structure is
 * copied.</p>
 *
 * <h2>Atomic graph publish</h2>
 * <p>Both {@link #bind(DawProject)} and {@link #unbind()} publish the whole
 * transport/mixer/tracks graph in a <em>single</em>
 * {@link AudioEngine#setGraph} call — one volatile store of one immutable
 * graph record. The RT callback snapshots that record with one volatile
 * load, so it sees either the entire old graph or the entire new graph;
 * there is no half-swapped state for {@code playbackActive} to gate on.
 * (The earlier gate-off → swap → gate-on sequencing of individual setters
 * could not guarantee this: the callback took three separate volatile
 * loads, so it could read the old, still-playing transport immediately
 * before the gate-off and then the new tracks/mixer after the following
 * stores — a hybrid graph. Atomicity supersedes ordering.)</p>
 *
 * <h2>Threading contract</h2>
 * <p>{@link #bind(DawProject)} and {@link #unbind()} are lifecycle-thread
 * operations (the FX / lifecycle thread) — never call them from the
 * real-time audio callback. {@link AudioEngine#setGraph} performs its
 * non-RT mixer preparation and then a single volatile store, which is what
 * publishes the new graph to the RT callback's next {@code processBlock}.
 * The tracks-refresh listener runs on whichever thread performed the
 * project mutation (the UI thread in production); allocating the snapshot
 * copy there is fine — {@link AudioEngine#setTracks} folds it into a fresh
 * graph record under the engine's mutator lock.</p>
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
     * project's tracks listener, then publishes the project's live
     * transport, live mixer, and an immutable tracks snapshot in a single
     * atomic {@link AudioEngine#setGraph} call — one volatile store of one
     * immutable graph record, so a rebind while the previous project is
     * still playing can never expose a half-swapped graph to the RT
     * callback (there is no half-swapped state; see the class javadoc).
     * Also registers the structural-change refresh listener,
     * {@linkplain #refreshPerformanceMonitor() refreshes} the
     * {@link PerformanceMonitor}, and increments the binding epoch.
     *
     * @param project the project to bind; must not be {@code null}
     */
    public void bind(DawProject project) {
        Objects.requireNonNull(project, "project must not be null");
        detachTrackListener();

        engine.setGraph(project.getTransport(), project.getMixer(),
                List.copyOf(project.getTracks()));

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
     * then clears the whole graph in a single atomic
     * {@link AudioEngine#setGraph} call — transport, mixer, and tracks all
     * null in one volatile store, killing {@code playbackActive} and the
     * rest of the render configuration together. Idempotent; does not
     * increment the epoch.
     */
    public void unbind() {
        detachTrackListener();
        engine.setGraph(null, null, null);
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
