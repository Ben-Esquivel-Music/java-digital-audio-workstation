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
import java.util.function.Consumer;

/** Arm validation owns the driver walk on a virtual thread (JEP 444, final in Java 21). */
public final class InputRoutingGuard implements AutoCloseable {
    private final DawProject project;
    private final AudioEngine engine;
    private final FxDispatcher dispatcher;
    private final Consumer<String> errors;
    private final Consumer<TrackCommand> commandSink;
    private final Map<Track, Runnable> listeners = new IdentityHashMap<>();
    private final Map<Track, Long> generations = new IdentityHashMap<>();
    private final Runnable unregisterProject;
    private boolean closed;
    private Track accepting;
    private record PendingArm(Runnable accept, long generation, CaptureRoutingPlan.Route route, StreamingProvision provision) { }
    private final Map<Track, PendingArm> pendingArms = new LinkedHashMap<>();
    private boolean validationRunning;
    public void cancelArm(Track track) { generations.merge(track, 1L, Long::sum); pendingArms.remove(track); }

    /**
     * @param commandSink the shared intent sink; it must execute commands synchronously
     *                    on the FX thread so validated re-entry stays within its acceptance scope
     */
    public InputRoutingGuard(DawProject project, AudioEngine engine, FxDispatcher dispatcher,
                             Consumer<String> errors, Consumer<TrackCommand> commandSink) {
        this.project = project; this.engine = engine; this.dispatcher = dispatcher; this.errors = errors;
        this.commandSink = Objects.requireNonNull(commandSink);
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
                Runnable check = () -> validateMutation(track);
                if (dispatcher.isFxThread()) check.run(); else dispatcher.onFx(check);
            }));
            if (track.isArmed()) validateMutation(track);
        }
    }
    private void validateMutation(Track track) {
        if (closed) return;
        if (!track.isArmed()) { cancelArm(track); return; }
        if (track.getType() == TrackType.MIDI || track.getInputRouting().isNone()) return;
        accepting = track;
        try { commandSink.accept(new ToggleArmCommand(track, false)); } finally { accepting = null; }
        requestArm(track, () -> commandSink.accept(new ToggleArmCommand(track, true)));
    }
    public void requestArm(Track track, Runnable accept) {
        if (closed) return;
        if (accepting == track || track.getType() == TrackType.MIDI || track.getInputRouting().isNone()) { accept.run(); return; }
        long generation = generations.merge(track, 1L, Long::sum);
        pendingArms.put(track, new PendingArm(accept, generation, CaptureRoutingPlan.snapshot(List.of(track)).getFirst(), engine.getStreamingProvision()));
        startNextArm();
    }
    private void startNextArm() {
        if (closed || validationRunning || pendingArms.isEmpty()) return;
        var entry = pendingArms.entrySet().iterator().next();
        Track track = entry.getKey(); PendingArm request = entry.getValue();
        List<Track> proposed = project.getTracks().stream()
                .filter(t -> t.getType() != TrackType.MIDI && (t.isArmed() || t == track)).toList();
        List<CaptureRoutingPlan.Route> snapshot = CaptureRoutingPlan.snapshot(proposed);
        List<Boolean> armedStates = proposed.stream().map(Track::isArmed).toList();
        validationRunning = true;
        Thread.ofVirtual().name("daw-input-arm-validation").start(() -> {
            Throwable failure = null;
            try {
                engine.validateInputRoutingSnapshots(snapshot, request.provision(), true);
            } catch (RuntimeException | Error e) { failure = e; }
            Throwable result = failure;
            dispatcher.onFx(() -> {
                validationRunning = false;
                if (closed) return;
                boolean current = pendingArms.get(track) == request && project.getTracks().contains(track)
                        && Objects.equals(generations.get(track), request.generation()) && request.route().matches(track)
                        && engine.getStreamingProvision() == request.provision();
                if (current) {
                    boolean siblingsChanged = false;
                    for (int i = 0; i < proposed.size(); i++)
                        if (!snapshot.get(i).matches(proposed.get(i)) || !project.getTracks().contains(proposed.get(i))
                                || armedStates.get(i) != proposed.get(i).isArmed()) siblingsChanged = true;
                    if (siblingsChanged) { startNextArm(); return; }
                    pendingArms.remove(track);
                    if (result == null) {
                        accepting = track;
                        try { request.accept().run(); } finally { accepting = null; }
                    } else {
                        commandSink.accept(new ToggleArmCommand(track, false));
                        String message = result.getMessage();
                        errors.accept(message == null || message.isBlank() ? result.getClass().getName() : message);
                    }
                } else if (pendingArms.get(track) == request) pendingArms.remove(track);
                startNextArm();
            });
        });
    }
    @Override public void close() {
        closed = true; pendingArms.clear(); unregisterProject.run();
        listeners.values().forEach(Runnable::run); listeners.clear(); generations.clear();
    }
}
