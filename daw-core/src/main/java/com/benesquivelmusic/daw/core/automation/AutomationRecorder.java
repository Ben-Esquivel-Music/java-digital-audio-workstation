package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.core.track.AutomationMode;
import com.benesquivelmusic.daw.core.undo.CompoundUndoableAction;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Captures parameter changes into automation lanes while a track is in a
 * write-enabled {@link AutomationMode} ({@link AutomationMode#WRITE WRITE},
 * {@link AutomationMode#LATCH LATCH}, or {@link AutomationMode#TOUCH TOUCH}).
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * AutomationRecorder recorder = new AutomationRecorder(track.getAutomationData());
 * recorder.beginRecording(AutomationMode.LATCH);
 * // during playback, for each fader/knob movement the UI calls:
 * recorder.recordValue(AutomationParameter.VOLUME, currentBeat, newValue);
 * // when the user releases a control in TOUCH mode, the UI calls:
 * recorder.touchEnd(AutomationParameter.VOLUME, currentBeat);
 * // at stop:
 * UndoableAction undoable = recorder.finishRecording("Record Volume Automation");
 * undoManager.push(undoable);
 * }</pre>
 *
 * <h2>Mode semantics</h2>
 * <ul>
 *   <li><b>{@link AutomationMode#WRITE WRITE}</b> — every call to
 *       {@code recordValue} removes any existing points inside the current
 *       recording window (from the first timestamp seen for a target up to
 *       the most recently recorded timestamp) and writes new breakpoints.
 *       The window extends forward as new values arrive.</li>
 *   <li><b>{@link AutomationMode#LATCH LATCH}</b> — the first call to
 *       {@code recordValue} for a target locks the start time; from that
 *       point on, every subsequent value is written (and any pre-existing
 *       points inside the window are removed) until
 *       {@link #finishRecording(String)} is called.</li>
 *   <li><b>{@link AutomationMode#TOUCH TOUCH}</b> — behaves like {@code LATCH}
 *       while a control is being held, but a call to
 *       {@link #touchEnd(AutomationTarget, double)} ends the window for that
 *       target: no further values are written for that target even if
 *       additional {@code recordValue} calls arrive before the next touch.</li>
 * </ul>
 *
 * <p>All recorded changes are collected into a single
 * {@link CompoundUndoableAction} returned by {@link #finishRecording(String)}
 * so that a whole recording pass can be undone or redone atomically.</p>
 *
 * <p>This class is not thread-safe. It is intended to be driven from the UI
 * thread (which already serializes control interactions).</p>
 */
public final class AutomationRecorder {

    private final AutomationData automationData;
    private final Map<AutomationTarget, Window> windows = new HashMap<>();
    private final List<UndoableAction> pendingActions = new ArrayList<>();
    private final Set<AutomationTarget> touchEnded = new HashSet<>();

    private AutomationMode mode;
    private boolean recording;

    /**
     * Creates a recorder that writes into the given {@link AutomationData}.
     *
     * @param automationData the track's automation data
     */
    public AutomationRecorder(AutomationData automationData) {
        this.automationData = Objects.requireNonNull(automationData,
                "automationData must not be null");
    }

    /**
     * Starts a new recording pass in the given mode.
     *
     * @param mode one of {@link AutomationMode#WRITE},
     *             {@link AutomationMode#LATCH}, {@link AutomationMode#TOUCH}
     * @throws IllegalArgumentException if {@code mode} is not a writing mode
     * @throws IllegalStateException    if a recording pass is already active
     */
    public void beginRecording(AutomationMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        if (!mode.writesAutomation()) {
            throw new IllegalArgumentException(
                    "mode is not a writing mode: " + mode);
        }
        if (recording) {
            throw new IllegalStateException("recording already in progress");
        }
        this.mode = mode;
        this.recording = true;
        this.windows.clear();
        this.pendingActions.clear();
        this.touchEnded.clear();
    }

    /** Returns whether a recording pass is currently active. */
    public boolean isRecording() {
        return recording;
    }

    /** Returns the active mode, or {@code null} if not recording. */
    public AutomationMode getMode() {
        return mode;
    }

    /**
     * Records a parameter change for a mixer-channel parameter. Convenience
     * overload of {@link #recordValue(AutomationTarget, double, double)}.
     */
    public void recordValue(AutomationParameter parameter,
                            double timeInBeats, double value) {
        recordValue((AutomationTarget) parameter, timeInBeats, value);
    }

    /**
     * Records a parameter change for a plugin parameter. Convenience overload
     * of {@link #recordValue(AutomationTarget, double, double)}.
     */
    public void recordValue(PluginParameterTarget target,
                            double timeInBeats, double value) {
        recordValue((AutomationTarget) target, timeInBeats, value);
    }

    /**
     * Records a parameter change at the given transport time.
     *
     * <p>Behaviour depends on the active {@link AutomationMode}: see the
     * class-level documentation for details.</p>
     *
     * @param target      the target being automated
     * @param timeInBeats the current transport position in beats
     * @param value       the new parameter value (will be clamped to the
     *                    target's valid range)
     * @throws IllegalStateException if no recording pass is active
     */
    public void recordValue(AutomationTarget target,
                            double timeInBeats, double value) {
        Objects.requireNonNull(target, "target must not be null");
        if (!recording) {
            throw new IllegalStateException("beginRecording must be called first");
        }
        if (timeInBeats < 0.0) {
            throw new IllegalArgumentException(
                    "timeInBeats must be >= 0: " + timeInBeats);
        }
        if (mode == AutomationMode.TOUCH && touchEnded.contains(target)) {
            // Touch window already closed for this target; ignore further events.
            return;
        }
        double clamped = clamp(target, value);

        AutomationLane lane = laneFor(target);

        Window window = windows.computeIfAbsent(target,
                t -> new Window(timeInBeats));

        // Remove any pre-existing points strictly inside the window that we
        // previously did not own — these must be recorded as RemovePoint
        // actions so that undo re-adds them.
        removeExistingPointsInsideWindow(lane, window, timeInBeats);

        AutomationPoint point = new AutomationPoint(timeInBeats, clamped);
        lane.addPoint(point);
        pendingActions.add(new AddAutomationPointAction(lane, point));
        // The point was added outside the action framework (to avoid re-adding
        // it during finishRecording). We still register an undo-only action
        // below — but addPoint was called manually, so we also need to make
        // sure finishRecording's execute() won't double-add. We therefore use
        // a ready-made action rather than letting the framework execute it.
        window.lastRecordedTime = timeInBeats;
        window.recordedPoints.add(point);
    }

    /**
     * Signals that the user has released the control for the given target
     * (only meaningful in {@link AutomationMode#TOUCH TOUCH} mode). After
     * this call, subsequent {@code recordValue} calls for {@code target} are
     * ignored for the remainder of the pass.
     *
     * <p>In non-TOUCH modes this method is a no-op.</p>
     *
     * @param target      the target whose control was released
     * @param timeInBeats the current transport position in beats
     */
    public void touchEnd(AutomationTarget target, double timeInBeats) {
        Objects.requireNonNull(target, "target must not be null");
        if (!recording || mode != AutomationMode.TOUCH) {
            return;
        }
        touchEnded.add(target);
        Window window = windows.get(target);
        if (window != null) {
            window.lastRecordedTime = Math.max(window.lastRecordedTime, timeInBeats);
        }
    }

    /**
     * Ends the current recording pass and returns a single undoable action
     * representing every point added or removed during the pass.
     *
     * <p>The returned action has already been executed (the lane is already
     * in its post-recording state). Callers should push the action onto the
     * undo stack <em>without</em> executing it again — e.g.
     * {@link com.benesquivelmusic.daw.core.undo.UndoManager#push(UndoableAction)}.</p>
     *
     * @param description human-readable description for the undo entry
     *                    (e.g. {@code "Record Volume Automation"})
     * @return a compound undoable action, or {@code null} if the pass recorded
     *         no changes (in which case nothing needs to be pushed onto the
     *         undo stack)
     * @throws IllegalStateException if no pass is active
     */
    public UndoableAction finishRecording(String description) {
        Objects.requireNonNull(description, "description must not be null");
        if (!recording) {
            throw new IllegalStateException("no recording in progress");
        }
        recording = false;
        List<UndoableAction> actions = List.copyOf(pendingActions);
        pendingActions.clear();
        windows.clear();
        touchEnded.clear();
        mode = null;
        if (actions.isEmpty()) {
            return null;
        }
        return new AlreadyExecutedCompoundAction(description, actions);
    }

    private AutomationLane laneFor(AutomationTarget target) {
        return switch (target) {
            case AutomationParameter parameter ->
                    automationData.getOrCreateLane(parameter);
            case PluginParameterTarget pluginTarget ->
                    automationData.getOrCreatePluginLane(pluginTarget);
            case ObjectParameterTarget objectTarget ->
                    automationData.getOrCreateObjectLane(objectTarget);
        };
    }

    private static double clamp(AutomationTarget target, double value) {
        return Math.max(target.getMinValue(), Math.min(target.getMaxValue(), value));
    }

    /**
     * Removes pre-existing points (i.e. points the recorder did not itself
     * add earlier in this pass) that fall within the recording window
     * {@code [window.start, time]}. Each removal is registered as a
     * {@link RemoveAutomationPointAction} so it can be undone.
     */
    private void removeExistingPointsInsideWindow(AutomationLane lane,
                                                  Window window, double time) {
        List<AutomationPoint> existing = new ArrayList<>(lane.getPoints());
        for (AutomationPoint p : existing) {
            if (window.recordedPoints.contains(p)) {
                continue;
            }
            double t = p.getTimeInBeats();
            if (t >= window.startTime && t <= time) {
                lane.removePoint(p);
                pendingActions.add(new RemoveAutomationPointAction(lane, p));
            }
        }
    }

    /** State held per target during the recording pass. */
    private static final class Window {
        final double startTime;
        double lastRecordedTime;
        final List<AutomationPoint> recordedPoints = new ArrayList<>();

        Window(double startTime) {
            this.startTime = startTime;
            this.lastRecordedTime = startTime;
        }
    }

    /**
     * Compound action whose {@link #execute()} is a no-op the first time,
     * because the recorder already applied every child action as values
     * streamed in. Subsequent {@code execute()} calls (redo) forward to the
     * normal compound behaviour.
     */
    private static final class AlreadyExecutedCompoundAction implements UndoableAction {

        private final CompoundUndoableAction delegate;
        private boolean firstExecute = true;

        AlreadyExecutedCompoundAction(String description,
                                      List<UndoableAction> children) {
            this.delegate = new CompoundUndoableAction(description, children);
        }

        @Override
        public String description() {
            return delegate.description();
        }

        @Override
        public void execute() {
            if (firstExecute) {
                firstExecute = false;
                // Lane already reflects the recorded state — avoid re-adding
                // points, which would violate the "at most one lane per
                // target" invariant.
                return;
            }
            delegate.execute();
        }

        @Override
        public void undo() {
            delegate.undo();
            // After an undo, a subsequent redo should re-execute normally.
            firstExecute = false;
        }
    }
}
