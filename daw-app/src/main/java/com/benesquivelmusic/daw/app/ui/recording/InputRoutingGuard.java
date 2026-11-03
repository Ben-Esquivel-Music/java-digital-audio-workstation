package com.benesquivelmusic.daw.app.ui.recording;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleArmCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.CaptureRoutingPlan;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Arm validation owns the driver walk on a virtual thread (JEP 444, final in Java 21).
 *
 * <p>The origin rule (PR #989 review 5711460812): a user's arm is an intent, so when its
 * validation lands while {@code projectActionsAllowed} says no — a project is being installed,
 * or a close of the open project waits on disk work — it is not completed, the track stays
 * unarmed, and that is logged at INFO; the guard's own corrective disarm and re-arm keep the
 * model consistent and are never refused.</p>
 */
public final class InputRoutingGuard implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(InputRoutingGuard.class.getName());
    private final DawProject project;
    private final AudioEngine engine;
    private final FxDispatcher dispatcher;
    private final Consumer<String> errors;
    private final Consumer<TrackCommand> commandSink;
    private final BooleanSupplier projectActionsAllowed;
    private final Map<Track, Runnable> listeners = new IdentityHashMap<>();
    private final Map<Track, Long> generations = new IdentityHashMap<>();
    private final Runnable unregisterProject;
    private boolean closed;
    private Track accepting;
    /**
     * A queued arm. The provision is deliberately not frozen here: each validation run reads the
     * engine's current provision when it starts, so a request that outlives a provision swap is
     * re-validated against the replacement instead of being dropped (track silently left disarmed)
     * or accepted on a proof taken against the outgoing provision. {@code userIntent} says whether
     * the user asked for it ({@link #requestArm}) or the guard re-arms a track it disarmed itself.
     */
    private record PendingArm(Runnable accept, long generation, CaptureRoutingPlan.Route route,
                              boolean userIntent) { }
    private final Map<Track, PendingArm> pendingArms = new LinkedHashMap<>();
    private boolean validationRunning;
    public void cancelArm(Track track) { generations.merge(track, 1L, Long::sum); pendingArms.remove(track); }

    /**
     * @param commandSink           the shared intent sink, ungated: it carries the guard's corrective
     *                              disarm and re-arm; it must execute commands synchronously on the FX
     *                              thread so validated re-entry stays within its acceptance scope
     * @param projectActionsAllowed whether a user's arm whose validation lands now may complete
     *                              (the window's edit gate; PR #989 review 5711460812)
     */
    public InputRoutingGuard(DawProject project, AudioEngine engine, FxDispatcher dispatcher,
                             Consumer<String> errors, Consumer<TrackCommand> commandSink,
                             BooleanSupplier projectActionsAllowed) {
        this.project = project; this.engine = engine; this.dispatcher = dispatcher; this.errors = errors;
        this.commandSink = Objects.requireNonNull(commandSink);
        this.projectActionsAllowed = Objects.requireNonNull(projectActionsAllowed);
        unregisterProject = project.addChangeListener(kind -> { if (kind == DawProject.ChangeKind.TRACKS) { if (dispatcher.isFxThread()) reconcile(); else dispatcher.onFx(this::reconcile); } });
        reconcile();
    }
    private void reconcile() {
        if (closed) return;
        for (Track track : List.copyOf(listeners.keySet())) if (!project.getTracks().contains(track)) {
            listeners.remove(track).run(); generations.remove(track);
        }
        for (Track track : project.getTracks()) if (!listeners.containsKey(track)) {
            listeners.put(track, track.addChangeListener(kind -> {
                if (accepting == track || kind != Track.ChangeKind.ARM && kind != Track.ChangeKind.INPUT_ROUTING) return;
                Runnable check = () -> validateMutation(track, kind);
                if (dispatcher.isFxThread()) check.run(); else dispatcher.onFx(check);
            }));
            if (track.isArmed()) validateMutation(track, Track.ChangeKind.ARM);
        }
    }
    /**
     * Re-validates a track after an arm or input-routing change. An armed track is disarmed and its arm
     * re-requested. A routing change while the track's arm is still pending (its validation in flight
     * or queued) supersedes that validation rather than cancelling it: the arm intent survives and the
     * LATEST routing is validated. The superseded reply is stale (its generation and route no longer
     * match) and can neither arm the track nor report an error.
     */
    private void validateMutation(Track track, Track.ChangeKind kind) {
        if (closed) return;
        engine.refreshInputMeterRouting();
        if (!track.isArmed()) {
            PendingArm pending = pendingArms.get(track);
            cancelArm(track);
            if (kind == Track.ChangeKind.INPUT_ROUTING && pending != null) {
                request(track, pending.accept(), pending.userIntent());
            }
            return;
        }
        if (track.getType() == TrackType.MIDI || track.getInputRouting().isNone()) return;
        accepting = track;
        try { commandSink.accept(new ToggleArmCommand(track, false)); } finally { accepting = null; }
        request(track, () -> commandSink.accept(new ToggleArmCommand(track, true)), false);
    }
    /** The user's arm, from the intent handler's arm validator: a user intent (the origin rule). */
    public void requestArm(Track track, Runnable accept) {
        request(track, accept, true);
    }
    private void request(Track track, Runnable accept, boolean userIntent) {
        if (closed) return;
        if (accepting == track || track.getType() == TrackType.MIDI || track.getInputRouting().isNone()) { accept.run(); return; }
        long generation = generations.merge(track, 1L, Long::sum);
        pendingArms.put(track, new PendingArm(accept, generation,
                CaptureRoutingPlan.snapshot(List.of(track)).getFirst(), userIntent));
        startNextArm();
    }
    private void startNextArm() {
        if (closed || validationRunning || pendingArms.isEmpty()) return;
        Map.Entry<Track, PendingArm> entry = pendingArms.entrySet().iterator().next();
        Track track = entry.getKey(); PendingArm request = entry.getValue();
        List<Track> proposed = project.getTracks().stream()
                .filter(t -> t.getType() != TrackType.MIDI && (t.isArmed() || t == track)).toList();
        List<CaptureRoutingPlan.Route> snapshot = CaptureRoutingPlan.snapshot(proposed);
        List<Boolean> armedStates = proposed.stream().map(Track::isArmed).toList();
        StreamingProvision provision = engine.getStreamingProvision();
        validationRunning = true;
        Thread.ofVirtual().name("daw-input-arm-validation").start(() -> {
            Throwable failure = null;
            try {
                engine.validateInputRoutingSnapshots(snapshot, provision, true);
            } catch (RuntimeException | Error e) { failure = e; }
            Throwable result = failure;
            dispatcher.onFx(() -> {
                validationRunning = false;
                if (closed) return;
                boolean current = pendingArms.get(track) == request && project.getTracks().contains(track)
                        && Objects.equals(generations.get(track), request.generation()) && request.route().matches(track);
                if (current) {
                    // A provision swapped while this run was in flight makes its verdict stale either
                    // way (including the engine's own "configuration changed" refusal): re-run the same
                    // request against the provision installed now rather than drop or accept it.
                    boolean stale = engine.getStreamingProvision() != provision;
                    for (int i = 0; i < proposed.size(); i++)
                        if (!snapshot.get(i).matches(proposed.get(i)) || !project.getTracks().contains(proposed.get(i))
                                || armedStates.get(i) != proposed.get(i).isArmed()) stale = true;
                    if (stale) { startNextArm(); return; }
                    pendingArms.remove(track);
                    if (result == null) {
                        if (request.userIntent() && !projectActionsAllowed.getAsBoolean()) {
                            LOG.info(() -> "Arming “" + track.getName()
                                    + "” was not completed: the project could not be edited.");
                        } else {
                            accepting = track;
                            try { request.accept().run(); } finally { accepting = null; }
                        }
                    } else {
                        disarmAfterAFailedValidation(track, result);
                    }
                } else if (pendingArms.get(track) == request) pendingArms.remove(track);
                startNextArm();
            });
        });
    }
    /** A failed validation leaves the track disarmed and reports why: a correctness write, never refused. */
    private void disarmAfterAFailedValidation(Track track, Throwable result) {
        commandSink.accept(new ToggleArmCommand(track, false));
        String message = result.getMessage();
        errors.accept(message == null || message.isBlank() ? result.getClass().getName() : message);
    }
    @Override public void close() {
        closed = true; pendingArms.clear(); unregisterProject.run();
        listeners.values().forEach(Runnable::run); listeners.clear(); generations.clear();
    }
}
