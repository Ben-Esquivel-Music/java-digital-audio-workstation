package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.project.DawProject.ChangeKind;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The observable view-model mirror of a {@link DawProject} — Stage 4 of the §8
 * migration path (Control Synchronization Design Book §3.1, §3.2, §3.3, §4.3;
 * story 292). {@code ProjectVM} projects the three project-level facts the UI
 * binds to — the {@link #nameProperty() name}, the single
 * {@link #dirtyProperty() dirty} bit (§1.2's "one dirty bit"), and the
 * observable {@link #getTracks() track list} (§5.7) — plus a forward-declared
 * {@link #checkpointProperty() checkpoint} label.
 *
 * <p>{@code ProjectVM} is the adapter that lets the JavaFX-free core be observed
 * without putting {@code javafx.beans.*} into {@code daw-core} (§2.5, §3.2, §9).
 * It registers the core's toolkit-neutral
 * {@link DawProject#addChangeListener(Consumer) change signal} and, on each
 * {@link ChangeKind}, re-reads the affected slice and republishes it as a
 * read-only JavaFX {@code Property}. It is the <strong>single writer</strong> of
 * its properties (§2.4): the wrappers are private and only the exposed
 * {@code ReadOnly*Property} / unmodifiable {@code ObservableList} views are
 * handed to controls, so a control can bind but never write back — the
 * feedback-guard smell of §1.4 cannot arise.</p>
 *
 * <h2>The single dirty bit (§1.2, §7)</h2>
 *
 * <p>{@link #dirtyProperty()} is "the one dirty bit": the window title and the
 * autosave HUD bind it instead of each call site having to remember a
 * {@code markProjectDirty()} poke. The core fires {@link ChangeKind#DIRTY} only
 * on a genuine transition (an idempotent {@code markDirty} on an already-dirty
 * project announces nothing), so the property tracks {@link DawProject#isDirty()}
 * exactly.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>The core signal may fire on any thread. The {@code NAME} and {@code DIRTY}
 * slices re-read the live authority inside their write, so they take an inline
 * fast-path when the signal already arrives on the FX thread (the common case —
 * a UI edit calling {@code markDirty()}/{@code setName()}) and otherwise marshal
 * through the story-289 {@link FxDispatcher#onFx(Runnable)} (§4.5). The inline
 * path keeps the mirror faithful to {@link DawProject#isDirty()} /
 * {@link DawProject#getName()} within the <em>same</em> FX pulse, so a derived
 * Save-menu item or window title never reads a one-pulse-stale value. The
 * {@code TRACKS} slice carries a <em>captured</em> snapshot across the thread
 * boundary and so stays strictly deferred (see {@link #onCoreChange(ChangeKind)}).
 * {@code ProjectVM} carries no continuous (per-frame) value, so it opens no
 * {@link FxDispatcher.ContinuousDoubleChannel}.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The constructor registers the core signal and seeds every property with the
 * current project state. {@link #dispose()} unregisters the signal so no listener
 * leaks ({@code javafx-application-design} §3/§4/§11). The full-load cascade
 * (§5.7, {@code ProjectLoadCascade}) disposes the old {@code ProjectVM} and
 * builds a fresh one bound to the newly-loaded project.</p>
 */
public final class ProjectVM {

    private final DawProject project;
    private final FxDispatcher dispatcher;

    private final ReadOnlyStringWrapper name =
            new ReadOnlyStringWrapper(this, "name");
    private final ReadOnlyBooleanWrapper dirty =
            new ReadOnlyBooleanWrapper(this, "dirty");
    private final ReadOnlyStringWrapper checkpoint =
            new ReadOnlyStringWrapper(this, "checkpoint", "");

    /** Backing list mutated only by {@link #onCoreChange(ChangeKind)} on the FX thread. */
    private final ObservableList<Track> tracksBacking = FXCollections.observableArrayList();
    private final ObservableList<Track> tracks =
            FXCollections.unmodifiableObservableList(tracksBacking);

    /** Removal token returned by {@link DawProject#addChangeListener(Consumer)}. */
    private final Runnable unregister;

    private boolean disposed;

    /**
     * Creates a view-model bound to {@code project}, marshalling all property
     * writes through {@code dispatcher}.
     *
     * @param project    the authoritative project to mirror; must not be {@code null}
     * @param dispatcher the marshalling seam (story 289); must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public ProjectVM(DawProject project, FxDispatcher dispatcher) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");

        // Seed every property with the current state so a control binding shows
        // the correct value before the first signal arrives. getTracks() is a
        // *live* unmodifiable view, so snapshot it (as the TRACKS branch does)
        // in case the project is still being populated on another thread.
        name.set(project.getName());
        dirty.set(project.isDirty());
        tracksBacking.setAll(List.copyOf(project.getTracks()));

        this.unregister = project.addChangeListener(this::onCoreChange);
    }

    /**
     * Handles a core change signal. Runs on whatever thread mutated the project;
     * routes each affected slice onto the FX thread (§4.5).
     */
    private void onCoreChange(ChangeKind kind) {
        switch (kind) {
            case NAME -> applyOnFx(() -> name.set(project.getName()));
            case DIRTY -> applyOnFx(() -> dirty.set(project.isDirty()));
            case TRACKS -> {
                // Snapshot on the signaling (mutating) thread, not on the FX thread:
                // the signal may fire off the FX thread (e.g. a background project
                // load), and DawProject.getTracks() is a *live* unmodifiable view.
                // Reading it later inside setAll() on the FX thread while the loader
                // thread is still adding tracks would throw a
                // ConcurrentModificationException; copy once here and hand the
                // immutable snapshot across the thread boundary.
                //
                // Unlike NAME/DIRTY this carries a *captured* value, so it must NOT
                // take the inline fast-path: an inline FX update could apply a newer
                // snapshot before a still-queued off-thread one runs, leaving the
                // older snapshot as the final state. Strict onFx FIFO ordering keeps
                // the last snapshot last.
                List<Track> snapshot = List.copyOf(project.getTracks());
                dispatcher.onFx(() -> tracksBacking.setAll(snapshot));
            }
        }
    }

    /**
     * Applies a single-writer property write, running it inline when the caller is
     * already on the FX thread and otherwise marshalling it through
     * {@link FxDispatcher#onFx(Runnable)}. The inline fast-path is the one
     * sanctioned by {@code FxDispatcher}'s contract — callers that want it test
     * {@link Platform#isFxApplicationThread()} themselves rather than asking the
     * dispatcher to run inline (its {@code onFx} always enqueues, deliberately).
     * Running inline keeps the mirror faithful to the authority within a single FX
     * pulse, so a UI edit that calls {@code markDirty()} / {@code setName()} on the
     * FX thread updates this property — and anything derived from it (Save-menu
     * enablement, the window title) — synchronously instead of one pulse late.
     *
     * <p>Only slices whose write re-reads the live authority ({@code NAME},
     * {@code DIRTY}) use this; {@code TRACKS} carries a captured snapshot and stays
     * strictly deferred (see {@link #onCoreChange(ChangeKind)}). Because the write
     * re-reads the authority, an inline FX update interleaved with a still-queued
     * off-thread one still converges on the current value regardless of order.</p>
     */
    private void applyOnFx(Runnable write) {
        if (Platform.isFxApplicationThread()) {
            write.run();
        } else {
            dispatcher.onFx(write);
        }
    }

    // ── Read-only property views (the VM is the sole writer) ──────────────────

    /** The project name. Read-only; bound by the window title and status bar. */
    public ReadOnlyStringProperty nameProperty() {
        return name.getReadOnlyProperty();
    }

    /** Returns the current project name. */
    public String getName() {
        return name.get();
    }

    /**
     * The single dirty bit (§1.2, §7). Read-only; bound by the window title and
     * the autosave HUD.
     */
    public ReadOnlyBooleanProperty dirtyProperty() {
        return dirty.getReadOnlyProperty();
    }

    /** Returns whether the project has unsaved changes. */
    public boolean isDirty() {
        return dirty.get();
    }

    /**
     * The checkpoint label. Read-only; bound by the session-status strip.
     *
     * <p><strong>Forward-declared</strong> (seeded {@code ""}): {@link DawProject}
     * owns no checkpoint field today — the checkpoint fact lives in
     * {@code CheckpointManager} and is part of the Project Manager surface, whose
     * migration is deferred (the session-status strip is stories 295&ndash;299).
     * The property exists now so the status strip can bind it without a later API
     * break; its producer is wired when that surface migrates (mirrors the
     * forward-declared {@code ChannelVM.meterLevel} of story 291).</p>
     */
    public ReadOnlyStringProperty checkpointProperty() {
        return checkpoint.getReadOnlyProperty();
    }

    /** Returns the current checkpoint label (empty until the Project Manager surface migrates). */
    public String getCheckpoint() {
        return checkpoint.get();
    }

    /**
     * The observable, read-only track list in project order (§5.7). Bound by the
     * arrangement canvas and the mixer to know the set and order of tracks; the
     * per-track {@code TrackVM}/{@code ChannelVM} pairs are built separately by
     * {@link TrackChannelRegistry}. Mutated only by this VM on the FX thread, so
     * a bound view never observes a torn update.
     *
     * @return an unmodifiable observable view of the project's tracks
     */
    public ObservableList<Track> getTracks() {
        return tracks;
    }

    /**
     * Unregisters the core change signal so nothing leaks (story 292 AC:
     * "{@code dispose()} unregisters and closes any subscription — no leaked
     * listeners"). Idempotent — a second call is a no-op. After disposal the VM
     * receives no further signals; its properties retain their last values.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        unregister.run();
    }
}
