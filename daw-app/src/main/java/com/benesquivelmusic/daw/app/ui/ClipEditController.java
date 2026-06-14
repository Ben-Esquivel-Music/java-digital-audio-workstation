package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CutClipsAction;
import com.benesquivelmusic.daw.core.audio.DuplicateClipsAction;
import com.benesquivelmusic.daw.core.audio.NudgeClipsAction;
import com.benesquivelmusic.daw.core.audio.PasteClipsAction;
import com.benesquivelmusic.daw.core.audio.PitchShiftClipAction;
import com.benesquivelmusic.daw.core.audio.StretchQuality;
import com.benesquivelmusic.daw.core.audio.TimeStretchClipAction;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.project.edit.NudgeService;
import com.benesquivelmusic.daw.core.project.edit.NudgeSettings;
import com.benesquivelmusic.daw.core.project.edit.NudgeUnit;
import com.benesquivelmusic.daw.core.project.edit.RippleEditService;
import com.benesquivelmusic.daw.core.project.edit.RippleValidationException;
import com.benesquivelmusic.daw.core.project.edit.SlipEditService;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.CompoundUndoableAction;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Handles clip clipboard operations (copy, cut, paste, duplicate, delete)
 * and editor audio actions (trim, fade-in, fade-out).
 *
 * <p>Extracted from {@code MainController} to separate clip editing concerns
 * from the main coordinator.</p>
 */
final class ClipEditController {

    /** Surfaces a notification with an inline Undo affordance. */
    @FunctionalInterface
    interface NotificationWithUndo {
        void show(NotificationLevel level, String message, Runnable undoCallback);
    }

    /**
     * Story 293 — direct collaborators replacing the retired {@code Host}
     * callback-up interface (CONTROL_SYNCHRONIZATION_DESIGN_BOOK §9). A plain
     * data carrier of functional dependencies. This controller is init-only
     * (never reconstructed on project load), so the swappable {@code project} /
     * {@code undoManager} are {@link Supplier}s read fresh on every access; the
     * stable clipboard / selection models are passed by value. {@code editorView}
     * / {@code gridStepBeats} read live view-navigation state, and
     * {@code rippleMode} derives from the live project — all suppliers.
     *
     * @param project                authoritative project (live, swappable)
     * @param undoManager            the undo manager (live, swappable)
     * @param clipboardManager       the app clipboard (stable)
     * @param selectionModel         the selection model (stable)
     * @param refreshArrangementCanvas repaints the arrangement canvas
     * @param syncMenuState          rebuilds full menu enablement (undo/redo
     *                               buttons bind {@code HistoryVM} since story 293)
     * @param markProjectDirty       marks the project dirty
     * @param updateStatusBar        writes a status-bar message (text, icon)
     * @param showNotificationWithUndo surfaces a notification with inline Undo
     * @param showNotification       surfaces a plain notification (level, message)
     * @param editorView             the live editor view
     * @param rippleMode             the live ripple mode
     * @param gridStepBeats          grid step in beats (keyboard slip shortcuts)
     */
    record Deps(Supplier<com.benesquivelmusic.daw.core.project.DawProject> project,
                Supplier<UndoManager> undoManager,
                ClipboardManager clipboardManager,
                SelectionModel selectionModel,
                Runnable refreshArrangementCanvas,
                Runnable syncMenuState,
                Runnable markProjectDirty,
                BiConsumer<String, DawIcon> updateStatusBar,
                NotificationWithUndo showNotificationWithUndo,
                BiConsumer<NotificationLevel, String> showNotification,
                Supplier<EditorView> editorView,
                Supplier<RippleMode> rippleMode,
                DoubleSupplier gridStepBeats) {
    }

    private final Deps deps;

    ClipEditController(Deps deps) {
        this.deps = Objects.requireNonNull(deps, "deps must not be null");
    }

    void onCopy() {
        List<ClipboardEntry> selected = deps.selectionModel().getSelectedClips();
        if (selected.isEmpty()) {
            return;
        }
        deps.clipboardManager().copyClips(selected);
        deps.syncMenuState().run();
        deps.updateStatusBar().accept("Copied " + selected.size() + " clip(s)", DawIcon.COPY);
    }

    void onCut() {
        List<ClipboardEntry> selected = deps.selectionModel().getSelectedClips();
        if (selected.isEmpty()) {
            return;
        }
        deps.clipboardManager().copyClips(selected);
        List<Map.Entry<Track, AudioClip>> entries = new ArrayList<>();
        for (ClipboardEntry entry : selected) {
            entries.add(Map.entry(entry.sourceTrack(), entry.clip()));
        }
        UndoableAction action = buildDeleteAction(entries, "Cut");
        if (action == null) {
            return; // ripple validation failed — notification already shown
        }
        deps.undoManager().get().execute(action);
        deps.selectionModel().clearClipSelection();
        deps.refreshArrangementCanvas().run();
        deps.syncMenuState().run();
        deps.updateStatusBar().accept("Cut " + entries.size() + " clip(s)", DawIcon.CUT);
        deps.markProjectDirty().run();
    }

    void onPaste() {
        ClipboardManager clipboard = deps.clipboardManager();
        if (!clipboard.hasContent()) {
            return;
        }
        List<ClipboardEntry> entries = clipboard.getEntries();
        if (entries.isEmpty()) {
            return;
        }
        double playhead = deps.project().get().getTransport().getPositionInBeats();
        List<Track> currentTracks = deps.project().get().getTracks();
        List<Map.Entry<Track, AudioClip>> sourceEntries = new ArrayList<>();
        for (ClipboardEntry entry : entries) {
            Track resolved = resolveTrack(entry.sourceTrack(), currentTracks);
            if (resolved != null) {
                sourceEntries.add(Map.entry(resolved, entry.clip()));
            }
        }
        if (sourceEntries.isEmpty()) {
            return;
        }
        deps.undoManager().get().execute(new PasteClipsAction(sourceEntries, null, playhead));
        deps.refreshArrangementCanvas().run();
        deps.syncMenuState().run();
        deps.updateStatusBar().accept("Pasted " + sourceEntries.size() + " clip(s) at beat "
                + String.format("%.1f", playhead), DawIcon.PASTE);
        deps.markProjectDirty().run();
    }

    void onDuplicate() {
        List<ClipboardEntry> selected = deps.selectionModel().getSelectedClips();
        if (selected.isEmpty()) {
            return;
        }
        List<Map.Entry<Track, AudioClip>> entries = new ArrayList<>();
        for (ClipboardEntry entry : selected) {
            entries.add(Map.entry(entry.sourceTrack(), entry.clip()));
        }
        deps.undoManager().get().execute(new DuplicateClipsAction(entries));
        deps.refreshArrangementCanvas().run();
        deps.syncMenuState().run();
        deps.updateStatusBar().accept("Duplicated " + entries.size() + " clip(s)", null);
        deps.markProjectDirty().run();
    }

    /** Slips the selection one grid step to the left. */
    void onSlipLeftByGrid() {
        onSlipSelectionByBeats(-deps.gridStepBeats().getAsDouble());
    }

    /** Slips the selection one grid step to the right. */
    void onSlipRightByGrid() {
        onSlipSelectionByBeats(deps.gridStepBeats().getAsDouble());
    }

    /** Slips the selection one sample to the left (Ctrl+Shift+Left). */
    void onSlipLeftByFine() {
        onSlipSelectionByBeats(-sampleStepBeats());
    }

    /** Slips the selection one sample to the right (Ctrl+Shift+Right). */
    void onSlipRightByFine() {
        onSlipSelectionByBeats(sampleStepBeats());
    }

    /**
     * Slips the selected clip's content by the given beat delta. Audio clips
     * use their source-offset range; MIDI clips shift every note's start
     * column. Clamping and undo are delegated to {@link SlipEditService}.
     *
     * <p>If no clip is selected, or the delta collapses to zero after
     * clamping, the call is a no-op. When the requested delta is clamped
     * at an edge the user is notified.</p>
     *
     * <p>Story 139 — {@code docs/user-stories/139-slip-edit-within-clip.md}.</p>
     *
     * @param beatDelta the requested slip delta in beats (positive = slide
     *                  content right on the timeline)
     */
    void onSlipSelectionByBeats(double beatDelta) {
        if (beatDelta == 0.0) {
            return;
        }
        SelectionModel sm = deps.selectionModel();
        List<ClipboardEntry> audioEntries = sm.getSelectedClips();
        java.util.Map<MidiClip, Track> midiEntries = sm.getSelectedMidiClips();
        if (audioEntries.isEmpty() && midiEntries.isEmpty()) {
            return;
        }

        boolean anyHitEdge = false;
        int applied = 0;
        for (ClipboardEntry entry : audioEntries) {
            AudioClip clip = entry.clip();
            // Slip convention: +beatDelta slides CONTENT right on the timeline
            // which means DECREASING the source offset.
            double sourceLengthBeats = sourceLengthBeatsFor(clip);
            SlipEditService.SlipResult result = SlipEditService.buildAudioSlip(
                    entry.sourceTrack(), clip, -beatDelta, sourceLengthBeats);
            if (result.hasAction()) {
                deps.undoManager().get().execute(result.action());
                applied++;
            }
            if (result.hitEdge()) {
                anyHitEdge = true;
            }
        }
        for (MidiClip clip : midiEntries.keySet()) {
            int columnDelta = (int) Math.round(beatDelta / EditorView.BEATS_PER_COLUMN);
            SlipEditService.SlipResult result = SlipEditService.buildMidiSlip(
                    clip, columnDelta);
            if (result.hasAction()) {
                deps.undoManager().get().execute(result.action());
                applied++;
            }
            if (result.hitEdge()) {
                anyHitEdge = true;
            }
        }
        if (applied == 0 && !anyHitEdge) {
            return;
        }
        if (anyHitEdge) {
            deps.showNotification().accept(NotificationLevel.INFO,
                    "Slip clamped at source-window edge");
        }
        deps.refreshArrangementCanvas().run();
        deps.syncMenuState().run();
        deps.markProjectDirty().run();
    }

    /**
     * Computes the audio clip's total source length in beats from its native
     * rate metadata and the project tempo. Returns {@code 0.0} when the
     * length is unknown so {@link SlipEditService} treats the upper bound
     * as unbounded.
     */
    private double sourceLengthBeatsFor(AudioClip clip) {
        double bpm = deps.project().get().getTransport().getTempo();
        if (bpm <= 0) {
            return 0.0;
        }
        SourceRateMetadata meta = clip.getSourceRateMetadata();
        if (meta != null && meta.framesPerChannel() > 0 && meta.nativeRateHz() > 0) {
            double seconds = (double) meta.framesPerChannel() / meta.nativeRateHz();
            return seconds * (bpm / 60.0);
        }
        return 0.0;
    }

    /**
     * Returns one sample expressed in beats using the effective tempo at the
     * current playhead position.
     *
     * <p>Using the playhead beat (rather than the initial tempo) ensures the
     * step is accurate when the project has tempo-map changes — e.g. an
     * accelerando that is active at the moment the user presses
     * {@code Ctrl+Shift+Left/Right}.</p>
     */
    private double sampleStepBeats() {
        var transport = deps.project().get().getTransport();
        double playheadBeat = transport.getPositionInBeats();
        double bpm = transport.getTempoMap().getTempoAtBeat(playheadBeat);
        double sampleRate = deps.project().get().getFormat().sampleRate();
        if (bpm <= 0.0 || sampleRate <= 0.0) {
            return 0.0;
        }
        return bpm / (60.0 * sampleRate);
    }

    // ── Nudge (Issue 566) ────────────────────────────────────────────────────

    /** Nudge the current selection left by the configured {@link NudgeSettings}. */
    void onNudgeLeft() { nudgeSelection(-1.0); }

    /** Nudge the current selection right by the configured {@link NudgeSettings}. */
    void onNudgeRight() { nudgeSelection(+1.0); }

    /** Nudge the current selection left by 10× the configured nudge value. */
    void onNudgeLeftLarge() { nudgeSelection(-10.0); }

    /** Nudge the current selection right by 10× the configured nudge value. */
    void onNudgeRightLarge() { nudgeSelection(+10.0); }

    /** Nudge the current selection left by exactly one audio sample. */
    void onNudgeLeftSample() { nudgeSelectionBySample(-1.0); }

    /** Nudge the current selection right by exactly one audio sample. */
    void onNudgeRightSample() { nudgeSelectionBySample(+1.0); }

    /**
     * Nudges every selected audio clip by {@code directionMultiplier} times
     * the project's configured {@link NudgeSettings}. The whole group moves
     * through a single {@link NudgeClipsAction} — so undo/redo treats a
     * multi-clip nudge as one step (Issue 566 acceptance criterion).
     */
    private void nudgeSelection(double directionMultiplier) {
        List<java.util.Map.Entry<Track, AudioClip>> entries = resolveNudgeTargets();
        if (entries.isEmpty()) {
            return;
        }
        NudgeService.TimingContext ctx = buildTimingContext();
        if (ctx == null) {
            return;
        }
        NudgeSettings settings = deps.project().get().getNudgeSettings();
        double beatDelta = NudgeService.beatsFor(settings, ctx, directionMultiplier);
        applyNudge(entries, beatDelta, settings, directionMultiplier);
    }

    /**
     * Nudges every selected audio clip by exactly one sample, independent of
     * the configured {@link NudgeSettings} — drives the {@code Alt+Left/Right}
     * shortcut from Issue 566.
     */
    private void nudgeSelectionBySample(double directionMultiplier) {
        List<java.util.Map.Entry<Track, AudioClip>> entries = resolveNudgeTargets();
        if (entries.isEmpty()) {
            return;
        }
        NudgeService.TimingContext ctx = buildTimingContext();
        if (ctx == null) {
            return;
        }
        double beatDelta = NudgeService.beatsForOneSample(ctx, directionMultiplier);
        applyNudge(entries, beatDelta,
                new NudgeSettings(NudgeUnit.FRAMES, 1.0), directionMultiplier);
    }

    private void applyNudge(List<java.util.Map.Entry<Track, AudioClip>> entries,
                            double beatDelta,
                            NudgeSettings settings, double directionMultiplier) {
        NudgeClipsAction action = NudgeService.buildAction(entries, beatDelta);
        if (action == null) {
            return;
        }
        deps.undoManager().get().execute(action);
        deps.refreshArrangementCanvas().run();
        deps.syncMenuState().run();
        deps.markProjectDirty().run();

        String dir = directionMultiplier >= 0 ? "right" : "left";
        double mag = Math.abs(directionMultiplier);
        String magDesc = mag == 1.0 ? "" : (mag == 10.0 ? "10× " : String.format("%.1f× ", mag));
        String statusText = String.format("Nudged %d clip(s) %s%s by %s %s",
                entries.size(), magDesc, dir,
                formatAmount(settings.amount()),
                formatUnit(settings.unit(), settings.amount()));
        double appliedBeatDelta = action.getAppliedBeatDelta();
        if (Math.abs(Math.abs(appliedBeatDelta) - Math.abs(beatDelta)) > 1.0e-9) {
            statusText += String.format(" (clamped to %s)", formatBeatDelta(appliedBeatDelta));
        }
        deps.updateStatusBar().accept(statusText, null);
    }

    private static String formatBeatDelta(double beatDelta) {
        double absBeatDelta = Math.abs(beatDelta);
        return String.format("%.3f %s",
                absBeatDelta,
                absBeatDelta == 1.0 ? "beat" : "beats");
    }

    /**
     * Resolves the current selection to the list of audio clips to nudge.
     * Priority: explicit clip selection → clips contained in the current
     * time selection. Returns an empty list if nothing is selectable.
     */
    private List<java.util.Map.Entry<Track, AudioClip>> resolveNudgeTargets() {
        SelectionModel sm = deps.selectionModel();
        List<ClipboardEntry> selected = sm.getSelectedClips();
        if (!selected.isEmpty()) {
            List<java.util.Map.Entry<Track, AudioClip>> entries =
                    new ArrayList<>(selected.size());
            for (ClipboardEntry entry : selected) {
                entries.add(java.util.Map.entry(entry.sourceTrack(), entry.clip()));
            }
            return entries;
        }
        // Fall back to clips that overlap the current time selection
        // (time-range shifts its overlapping clips — Issue 566). Uses the
        // same overlap semantics as SelectionModel.selectClipsInRegion so
        // that "nudge clips in the time range" is consistent across the UI.
        if (sm.hasSelection()) {
            double s = sm.getStartBeat();
            double e = sm.getEndBeat();
            if (s < e) {
                List<java.util.Map.Entry<Track, AudioClip>> entries = new ArrayList<>();
                for (Track t : deps.project().get().getTracks()) {
                    for (AudioClip c : t.getClips()) {
                        if (c.getStartBeat() < e && c.getEndBeat() > s) {
                            entries.add(java.util.Map.entry(t, c));
                        }
                    }
                }
                return entries;
            }
        }
        return List.of();
    }

    /**
     * Builds the {@link NudgeService.TimingContext} from the project's
     * current transport state, or {@code null} if the context cannot be
     * built (e.g. zero tempo).
     */
    private NudgeService.TimingContext buildTimingContext() {
        var transport = deps.project().get().getTransport();
        // Use the tempo at the current playhead beat so tempo-map changes
        // (e.g. accelerando) affect FRAMES/MILLISECONDS nudges correctly,
        // mirroring sampleStepBeats().
        double playheadBeat = transport.getPositionInBeats();
        double bpm = transport.getTempoMap().getTempoAtBeat(playheadBeat);
        double sampleRate = deps.project().get().getFormat().sampleRate();
        double gridStep = deps.gridStepBeats().getAsDouble();
        double barBeats = transport.getTimeSignatureNumerator();
        if (bpm <= 0.0 || sampleRate <= 0.0 || gridStep <= 0.0 || barBeats <= 0.0) {
            return null;
        }
        return new NudgeService.TimingContext(bpm, sampleRate, gridStep, barBeats);
    }

    private static String formatAmount(double amount) {
        if (amount == Math.floor(amount) && !Double.isInfinite(amount)) {
            return Long.toString((long) amount);
        }
        return String.format("%.3f", amount);
    }

    private static String formatUnit(NudgeUnit unit, double amount) {
        boolean plural = amount != 1.0;
        return switch (unit) {
            case FRAMES        -> plural ? "samples" : "sample";
            case MILLISECONDS  -> "ms";
            case GRID_STEPS    -> plural ? "grid steps" : "grid step";
            case BAR_FRACTION  -> plural ? "bars" : "bar";
        };
    }

    void onDeleteSelection() {
        List<ClipboardEntry> selected = deps.selectionModel().getSelectedClips();
        if (selected.isEmpty()) {
            return;
        }
        List<Map.Entry<Track, AudioClip>> entries = new ArrayList<>();
        for (ClipboardEntry entry : selected) {
            entries.add(Map.entry(entry.sourceTrack(), entry.clip()));
        }
        UndoableAction action = buildDeleteAction(entries, "Delete");
        if (action == null) {
            return; // ripple validation failed — notification already shown
        }
        deps.undoManager().get().execute(action);
        deps.selectionModel().clearClipSelection();
        deps.refreshArrangementCanvas().run();
        deps.syncMenuState().run();
        deps.updateStatusBar().accept("Deleted " + entries.size() + " clip(s)", null);
        deps.markProjectDirty().run();
    }

    /**
     * Builds the undoable action for a delete/cut, routing through the
     * {@link RippleEditService} when the project's {@link RippleMode} is
     * active. Returns {@code null} if ripple validation rejects the edit;
     * in that case the user has already been notified.
     */
    private UndoableAction buildDeleteAction(
            List<Map.Entry<Track, AudioClip>> entries, String verb) {
        RippleMode mode = deps.rippleMode().get();
        if (mode == RippleMode.OFF) {
            return new CutClipsAction(entries);
        }
        SelectionBounds selection = rippleSelection(entries);
        try {
            return RippleEditService.buildRippleDelete(
                    entries, mode, deps.project().get().getTracks(),
                    selection.start(), selection.end());
        } catch (RippleValidationException e) {
            deps.showNotification().accept(NotificationLevel.ERROR,
                    verb + " cancelled — ripple would overlap clips: " + e.getMessage());
            return null;
        }
    }

    private SelectionBounds rippleSelection(List<Map.Entry<Track, AudioClip>> entries) {
        SelectionModel selectionModel = deps.selectionModel();
        if (!selectionModel.hasSelection()) {
            return SelectionBounds.NONE;
        }
        double selStart = selectionModel.getStartBeat();
        double selEnd = selectionModel.getEndBeat();
        for (Map.Entry<Track, AudioClip> entry : entries) {
            AudioClip clip = entry.getValue();
            if (clip.getStartBeat() < selEnd && clip.getEndBeat() > selStart) {
                return new SelectionBounds(OptionalDouble.of(selStart), OptionalDouble.of(selEnd));
            }
        }
        return SelectionBounds.NONE;
    }

    private record SelectionBounds(OptionalDouble start, OptionalDouble end) {
        private static final SelectionBounds NONE = new SelectionBounds(
                OptionalDouble.empty(), OptionalDouble.empty());
    }

    // ── Editor audio handle actions ─────────────────────────────────────────

    void onEditorTrim() {
        EditorView editorView = deps.editorView().get();
        Track track = editorView.getSelectedTrack();
        if (track == null || track.getClips().isEmpty()) {
            return;
        }
        List<AudioClip> clips = track.getClips();
        deps.undoManager().get().execute(new UndoableAction() {
            private final List<double[]> savedState = new ArrayList<>();
            {
                for (AudioClip clip : clips) {
                    savedState.add(new double[]{
                            clip.getStartBeat(), clip.getDurationBeats(),
                            clip.getSourceOffsetBeats()});
                }
            }
            @Override public String description() { return "Trim: " + track.getName(); }
            @Override public void execute() {
                for (AudioClip clip : clips) {
                    double trimAmount = clip.getDurationBeats() * 0.1;
                    if (trimAmount > 0 && clip.getDurationBeats() > trimAmount * 2) {
                        clip.trimTo(clip.getStartBeat() + trimAmount,
                                clip.getEndBeat() - trimAmount);
                    }
                }
            }
            @Override public void undo() {
                for (int i = 0; i < clips.size(); i++) {
                    AudioClip clip = clips.get(i);
                    double[] saved = savedState.get(i);
                    clip.setStartBeat(saved[0]);
                    clip.setDurationBeats(saved[1]);
                    clip.setSourceOffsetBeats(saved[2]);
                }
            }
        });
        deps.syncMenuState().run();
        editorView.getWaveformDisplay().refresh();
        deps.showNotificationWithUndo().show(NotificationLevel.SUCCESS,
                "Trimmed: " + track.getName(), () -> deps.undoManager().get().undo());
        deps.markProjectDirty().run();
    }

    void onEditorFadeIn() {
        EditorView editorView = deps.editorView().get();
        Track track = editorView.getSelectedTrack();
        if (track == null || track.getClips().isEmpty()) {
            return;
        }
        List<AudioClip> clips = track.getClips();
        double defaultFadeBeats = 2.0;
        deps.undoManager().get().execute(new UndoableAction() {
            private final List<double[]> savedFades = new ArrayList<>();
            {
                for (AudioClip clip : clips) {
                    savedFades.add(new double[]{clip.getFadeInBeats()});
                }
            }
            @Override public String description() { return "Fade In: " + track.getName(); }
            @Override public void execute() {
                for (AudioClip clip : clips) {
                    clip.setFadeInBeats(defaultFadeBeats);
                }
            }
            @Override public void undo() {
                for (int i = 0; i < clips.size(); i++) {
                    clips.get(i).setFadeInBeats(savedFades.get(i)[0]);
                }
            }
        });
        deps.syncMenuState().run();
        editorView.getWaveformDisplay().refresh();
        deps.showNotificationWithUndo().show(NotificationLevel.SUCCESS,
                "Fade in applied: " + track.getName(), () -> deps.undoManager().get().undo());
        deps.markProjectDirty().run();
    }

    void onEditorFadeOut() {
        EditorView editorView = deps.editorView().get();
        Track track = editorView.getSelectedTrack();
        if (track == null || track.getClips().isEmpty()) {
            return;
        }
        List<AudioClip> clips = track.getClips();
        double defaultFadeBeats = 2.0;
        deps.undoManager().get().execute(new UndoableAction() {
            private final List<double[]> savedFades = new ArrayList<>();
            {
                for (AudioClip clip : clips) {
                    savedFades.add(new double[]{clip.getFadeOutBeats()});
                }
            }
            @Override public String description() { return "Fade Out: " + track.getName(); }
            @Override public void execute() {
                for (AudioClip clip : clips) {
                    clip.setFadeOutBeats(defaultFadeBeats);
                }
            }
            @Override public void undo() {
                for (int i = 0; i < clips.size(); i++) {
                    clips.get(i).setFadeOutBeats(savedFades.get(i)[0]);
                }
            }
        });
        deps.syncMenuState().run();
        editorView.getWaveformDisplay().refresh();
        deps.showNotificationWithUndo().show(NotificationLevel.SUCCESS,
                "Fade out applied: " + track.getName(), () -> deps.undoManager().get().undo());
        deps.markProjectDirty().run();
    }

    // ── Time-stretch / Pitch-shift (Story 042) ───────────────────────────────

    /**
     * Functional callback that surfaces the time-stretch dialog and returns
     * the user's chosen settings. The controller invokes this on the JavaFX
     * application thread. Tests inject a stub so the operation logic can be
     * exercised without instantiating the FX dialog.
     *
     * @see TimeStretchClipDialog
     */
    @FunctionalInterface
    interface TimeStretchPrompter {
        /**
         * Prompts the user for time-stretch settings.
         *
         * @param sourceSeconds duration of the (first) selected clip in seconds,
         *                      used to pre-populate the target-duration field
         * @return the chosen settings, or empty if the user cancelled
         */
        java.util.Optional<TimeStretchClipDialog.Result> prompt(double sourceSeconds);
    }

    /** Prompter for the pitch-shift dialog. See {@link TimeStretchPrompter}. */
    @FunctionalInterface
    interface PitchShiftPrompter {
        java.util.Optional<PitchShiftClipDialog.Result> prompt();
    }

    /**
     * Story 042 — surfaces {@link TimeStretchClipDialog} for the currently
     * selected audio clip(s) and applies the chosen settings as a single
     * undoable step (a {@link CompoundUndoableAction} when more than one
     * clip is selected).
     *
     * <p>Per the issue, {@link com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata}
     * is preserved through the operation: the clip's native rate is unchanged
     * because the operation is musical (stretch ratio metadata only), not a
     * sample-rate change.</p>
     *
     * @param prompter callback that obtains the user's settings; supplied by
     *                 the host so tests can inject a deterministic stub
     */
    void onTimeStretchSelected(TimeStretchPrompter prompter) {
        Objects.requireNonNull(prompter, "prompter must not be null");
        List<AudioClip> clips = collectSelectedAudioClips();
        if (clips.isEmpty()) {
            deps.showNotification().accept(NotificationLevel.INFO,
                    "Select an audio clip to time-stretch");
            return;
        }
        double sourceSeconds = estimateClipSeconds(clips.get(0));
        java.util.Optional<TimeStretchClipDialog.Result> result = prompter.prompt(sourceSeconds);
        if (result.isEmpty()) {
            return;
        }
        TimeStretchClipDialog.Result r = result.get();
        applyAsCompound(clips, "Time Stretch " + clips.size() + " Clip(s)",
                clip -> new TimeStretchClipAction(clip, r.ratio(), r.quality()));
        deps.updateStatusBar().accept(String.format(java.util.Locale.ROOT,
                "Time-stretched %d clip(s) by %.3f×", clips.size(), r.ratio()), null);
    }

    /**
     * Story 042 — surfaces {@link PitchShiftClipDialog} for the currently
     * selected audio clip(s) and applies the chosen settings as a single
     * undoable step.
     *
     * @param prompter callback that obtains the user's settings; supplied by
     *                 the host so tests can inject a deterministic stub
     */
    void onPitchShiftSelected(PitchShiftPrompter prompter) {
        Objects.requireNonNull(prompter, "prompter must not be null");
        List<AudioClip> clips = collectSelectedAudioClips();
        if (clips.isEmpty()) {
            deps.showNotification().accept(NotificationLevel.INFO,
                    "Select an audio clip to pitch-shift");
            return;
        }
        java.util.Optional<PitchShiftClipDialog.Result> result = prompter.prompt();
        if (result.isEmpty()) {
            return;
        }
        PitchShiftClipDialog.Result r = result.get();
        double total = r.totalSemitones();
        StretchQuality quality = r.quality();
        applyAsCompound(clips, "Pitch Shift " + clips.size() + " Clip(s)",
                clip -> new PitchShiftClipAction(clip, total, quality));
        deps.updateStatusBar().accept(String.format(java.util.Locale.ROOT,
                "Pitch-shifted %d clip(s) by %+.2f semitones", clips.size(), total), null);
    }

    /**
     * Resolves the current selection to its audio clips, ignoring any MIDI
     * clips. Returns an empty list when nothing is selected.
     */
    private List<AudioClip> collectSelectedAudioClips() {
        List<ClipboardEntry> selected = deps.selectionModel().getSelectedClips();
        if (selected.isEmpty()) {
            return List.of();
        }
        List<AudioClip> clips = new ArrayList<>(selected.size());
        for (ClipboardEntry entry : selected) {
            clips.add(entry.clip());
        }
        return clips;
    }

    /**
     * Builds one {@link UndoableAction} per clip and either pushes it
     * directly (single clip) or wraps the lot in {@link CompoundUndoableAction}
     * (multi-clip) so the user sees one undo step.
     */
    private void applyAsCompound(List<AudioClip> clips, String description,
                                 java.util.function.Function<AudioClip, UndoableAction> factory) {
        UndoableAction action;
        if (clips.size() == 1) {
            action = factory.apply(clips.get(0));
        } else {
            List<UndoableAction> children = new ArrayList<>(clips.size());
            for (AudioClip clip : clips) {
                children.add(factory.apply(clip));
            }
            action = new CompoundUndoableAction(description, children);
        }
        deps.undoManager().get().execute(action);
        deps.refreshArrangementCanvas().run();
        deps.syncMenuState().run();
        deps.markProjectDirty().run();
    }

    /**
     * Estimates a clip's source duration in seconds, preferring its
     * {@link SourceRateMetadata} when available and falling back to the
     * timeline duration converted via the project tempo.
     */
    private double estimateClipSeconds(AudioClip clip) {
        SourceRateMetadata meta = clip.getSourceRateMetadata();
        if (meta != null && meta.framesPerChannel() > 0 && meta.nativeRateHz() > 0) {
            return (double) meta.framesPerChannel() / meta.nativeRateHz();
        }
        double bpm = deps.project().get().getTransport().getTempo();
        if (bpm > 0) {
            return clip.getDurationBeats() * 60.0 / bpm;
        }
        return clip.getDurationBeats();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Track resolveTrack(Track source, List<Track> currentTracks) {
        if (currentTracks.contains(source)) {
            return source;
        }
        for (Track t : currentTracks) {
            if (t.getId().equals(source.getId())) {
                return t;
            }
        }
        for (Track t : currentTracks) {
            if (t.getType() == source.getType()) {
                return t;
            }
        }
        return null;
    }
}
