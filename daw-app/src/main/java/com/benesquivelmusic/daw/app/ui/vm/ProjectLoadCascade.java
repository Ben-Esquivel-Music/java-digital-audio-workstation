package com.benesquivelmusic.daw.app.ui.vm;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/**
 * The <strong>ordered full-load cascade</strong> — the §1.6 flicker fix
 * (Control Synchronization Design Book §5.7; story 292). When a project is
 * loaded, many surfaces must update; doing it in "whatever order the load
 * handler happens to make" briefly renders a half-built world (the mixer shows
 * zero channels, the playhead clamps against an unknown length). This class
 * replaces that ad-hoc sequence with a <strong>single declared phase order</strong>
 * that a test can assert (§1.6: "the order is a named constant/enum so it can be
 * asserted").
 *
 * <h2>The declared order</h2>
 *
 * <p>{@link Phase} <em>is</em> the §5.7 sequence, and {@link #run()} executes the
 * phases strictly in {@link Phase#values() enum order}:</p>
 * <ol>
 *   <li>{@link Phase#TEAR_DOWN} — dispose the old VMs + view subscriptions.</li>
 *   <li>{@link Phase#BUILD_VMS} — build the model-derived VMs in order
 *       (ProjectVM → TrackVMs → ChannelVMs → TransportVM → HistoryVM).</li>
 *   <li>{@link Phase#REBUILD_VIEWS} — rebuild structural views (track lanes ▸
 *       mixer strips ▸ inserts/racks).</li>
 *   <li>{@link Phase#BIND_CONTINUOUS} — bind the continuous views (playhead,
 *       meters, time/tempo display).</li>
 *   <li>{@link Phase#RESTORE_SELECTION} — restore + clamp the selection to the
 *       new project's bounds ({@link SelectionVM#clampTo(ProjectVM)}).</li>
 *   <li>{@link Phase#ANNOUNCE} — announce {@code ProjectLoaded} last (status bar,
 *       lock indicator, checkpoint, window title, recents).</li>
 * </ol>
 *
 * <p>Running {@code BUILD_VMS} before {@code REBUILD_VIEWS} is what guarantees
 * the mixer never renders zero channels and the playhead never clamps against an
 * unknown length (§5.7).</p>
 *
 * <h2>The work per phase is injected</h2>
 *
 * <p>This class owns the <em>order</em>, not the per-phase work: each phase's
 * step is supplied as a {@link Runnable} (a phase with no step is a no-op slot
 * that still runs in sequence). That keeps the cascade a small, fully testable
 * unit — a test injects recording / clamp steps and asserts {@link
 * #executedPhases()} equals the declared order and that the selection clamp ran
 * in step&nbsp;5 — while the live composition root (story 293, the god-controller
 * retirement) supplies the real VM-rebuild, view-rebuild, continuous-bind, and
 * announce steps. Story 292 builds and proves the ordered seam; story 293 wires
 * the real steps into the project-open path that today fires the imperative
 * refreshers directly.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #run()} touches view-models and views, so it must be called on the
 * FX thread. It may be run again for each subsequent load; each run records a
 * fresh {@link #executedPhases()}.</p>
 */
public final class ProjectLoadCascade {

    /**
     * The fixed inter-surface phase order of the full-load cascade (§5.7). The
     * declaration order of these constants <em>is</em> the contract — {@link
     * #run()} iterates {@link #values()} — so reordering them reorders the
     * cascade, and a test pins the sequence.
     */
    public enum Phase {
        /** Dispose the old project's VMs and view subscriptions (remove listeners, §4). */
        TEAR_DOWN,
        /** Build the model-derived VMs in order: ProjectVM → TrackVMs → ChannelVMs → TransportVM → HistoryVM. */
        BUILD_VMS,
        /** Rebuild structural views in order: track lanes ▸ mixer strips ▸ inserts/racks. */
        REBUILD_VIEWS,
        /** Bind the continuous views: playhead, meters, time/tempo display. */
        BIND_CONTINUOUS,
        /** Restore + clamp the selection (and viewport) to the new project's bounds. */
        RESTORE_SELECTION,
        /** Announce {@code ProjectLoaded} last: status bar, lock indicator, checkpoint, window title, recents. */
        ANNOUNCE
    }

    private final EnumMap<Phase, Runnable> steps;
    private final List<Phase> executed = new ArrayList<>();

    private ProjectLoadCascade(EnumMap<Phase, Runnable> steps) {
        this.steps = steps;
    }

    /** Returns a builder for assembling the per-phase steps. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Runs the cascade: executes each {@link Phase}'s step (if any) strictly in
     * {@link Phase#values() declared order}, recording each completed phase. A
     * re-run first clears the previous {@link #executedPhases()}. Must be called
     * on the FX thread.
     */
    public void run() {
        executed.clear();
        for (Phase phase : Phase.values()) {
            Runnable step = steps.get(phase);
            if (step != null) {
                step.run();
            }
            executed.add(phase);
        }
    }

    /**
     * Returns the phases executed by the most recent {@link #run()}, in execution
     * order — i.e. exactly {@link Phase#values()} after a complete run. Exposed so
     * a test can assert the declared order without relying on render timing
     * (§1.6).
     *
     * @return an immutable snapshot of the executed phases
     */
    public List<Phase> executedPhases() {
        return List.copyOf(executed);
    }

    /** Builder that collects one {@link Runnable} step per {@link Phase}. */
    public static final class Builder {
        private final EnumMap<Phase, Runnable> steps = new EnumMap<>(Phase.class);

        /**
         * Registers the step to run for {@code phase}, replacing any previous step
         * for the same phase.
         *
         * @param phase the phase the step belongs to; must not be {@code null}
         * @param step  the work to run in that phase; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if either argument is {@code null}
         */
        public Builder on(Phase phase, Runnable step) {
            steps.put(
                    Objects.requireNonNull(phase, "phase must not be null"),
                    Objects.requireNonNull(step, "step must not be null"));
            return this;
        }

        /** Builds the cascade with the collected steps (any unset phase is a no-op slot). */
        public ProjectLoadCascade build() {
            return new ProjectLoadCascade(new EnumMap<>(steps));
        }
    }
}
