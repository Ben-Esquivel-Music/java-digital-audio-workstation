package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CutClipsAction;
import com.benesquivelmusic.daw.core.audio.DuplicateClipsAction;
import com.benesquivelmusic.daw.core.audio.NudgeClipsAction;
import com.benesquivelmusic.daw.core.audio.PasteClipsAction;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.project.edit.NudgeService;
import com.benesquivelmusic.daw.core.project.edit.NudgeSettings;
import com.benesquivelmusic.daw.core.project.edit.NudgeUnit;
import com.benesquivelmusic.daw.core.project.edit.RippleEditService;
import com.benesquivelmusic.daw.core.project.edit.RippleValidationException;
import com.benesquivelmusic.daw.core.project.edit.SlipEditService;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Handles clip clipboard operations (copy, cut, paste, duplicate, delete)
 * and editor audio actions (trim, fade-in, fade-out).
 *
 * <p>Extracted from {@code MainController} to separate clip editing concerns
 * from the main coordinator.</p>
 */
final class ClipEditController {

    interface Host {
        com.benesquivelmusic.daw.core.project.DawProject project();
        UndoManager undoManager();
        ClipboardManager clipboardManager();
        SelectionModel selectionModel();
        void refreshArrangementCanvas();
        void updateUndoRedoState();
        void syncMenuState();
        void markProjectDirty();
        void updateStatusBar(String text, DawIcon icon);
        void showNotificationWithUndo(NotificationLevel level, String message, Runnable undoCallback);
        void showNotification(NotificationLevel level, String message);
        EditorView editorView();
        RippleMode rippleMode();
        /** Returns the grid step in beats, used by keyboard slip shortcuts. */
        double gridStepBeats();
    }

    private final Host host;

    ClipEditController(Host host) {
        this.host = host;
    }

    void onCopy() {
        List<ClipboardEntry> selected = host.selectionModel().getSelectedClips();
        if (selected.isEmpty()) {
            return;
        }
        host.clipboardManager().copyClips(selected);
        host.syncMenuState();
        host.updateStatusBar("Copied " + selected.size() + " clip(s)", DawIcon.COPY);
    }

    void onCut() {
        List<ClipboardEntry> selected = host.selectionModel().getSelectedClips();
        if (selected.isEmpty()) {
            return;
        }
        host.clipboardManager().copyClips(selected);
        List<Map.Entry<Track, AudioClip>> entries = new ArrayList<>();
        for (ClipboardEntry entry : selected) {
            entries.add(Map.entry(entry.sourceTrack(), entry.clip()));
        }
        UndoableAction action = buildDeleteAction(entries, "Cut");
        if (action == null) {
            return; // ripple validation failed — notification already shown
        }
        host.undoManager().execute(action);
        host.selectionModel().clearClipSelection();
        host.refreshArrangementCanvas();
        host.updateUndoRedoState();
        host.updateStatusBar("Cut " + entries.size() + " clip(s)", DawIcon.CUT);
        host.markProjectDirty();
    }

    void onPaste() {
        ClipboardManager clipboard = host.clipboardManager();
        if (!clipboard.hasContent()) {
            return;
        }
        List<ClipboardEntry> entries = clipboard.getEntries();
        if (entries.isEmpty()) {
            return;
        }
        double playhead = host.project().getTransport().getPositionInBeats();
        List<Track> currentTracks = host.project().getTracks();
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
        host.undoManager().execute(new PasteClipsAction(sourceEntries, null, playhead));
        host.refreshArrangementCanvas();
        host.updateUndoRedoState();
        host.updateStatusBar("Pasted " + sourceEntries.size() + " clip(s) at beat "
                + String.format("%.1f", playhead), DawIcon.PASTE);
        host.markProjectDirty();
    }

    void onDuplicate() {
        List<ClipboardEntry> selected = host.selectionModel().getSelectedClips();
        if (selected.isEmpty()) {
            return;
        }
        List<Map.Entry<Track, AudioClip>> entries = new ArrayList<>();
        for (ClipboardEntry entry : selected) {
            entries.add(Map.entry(entry.sourceTrack(), entry.clip()));
        }
        host.undoManager().execute(new DuplicateClipsAction(entries));
        host.refreshArrangementCanvas();
        host.updateUndoRedoState();
        host.updateStatusBar("Duplicated " + entries.size() + " clip(s)", null);
        host.markProjectDirty();
    }

    /** Slips the selection one grid step to the left. */
    void onSlipLeftByGrid() {
        onSlipSelectionByBeats(-host.gridStepBeats());
    }

    /** Slips the selection one grid step to the right. */
    void onSlipRightByGrid() {
        onSlipSelectionByBeats(host.gridStepBeats());
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
        SelectionModel sm = host.selectionModel();
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
                    clip, -beatDelta, sourceLengthBeats);
            if (result.hasAction()) {
                host.undoManager().execute(result.action());
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
                host.undoManager().execute(result.action());
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
            host.showNotification(NotificationLevel.INFO,
                    "Slip clamped at source-window edge");
        }
        host.refreshArrangementCanvas();
        host.updateUndoRedoState();
        host.markProjectDirty();
    }

    /**
     * Computes the audio clip's total source length in beats from its native
     * rate metadata and the project tempo. Returns {@code 0.0} when the
     * length is unknown so {@link SlipEditService} treats the upper bound
     * as unbounded.
     */
    private double sourceLengthBeatsFor(AudioClip clip) {
        double bpm = host.project().getTransport().getTempo();
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
        var transport = host.project().getTransport();
        double playheadBeat = transport.getPositionInBeats();
        double bpm = transport.getTempoMap().getTempoAtBeat(playheadBeat);
        double sampleRate = host.project().getFormat().sampleRate();
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
        List<AudioClip> clips = resolveNudgeTargets();
        if (clips.isEmpty()) {
            return;
        }
        NudgeService.TimingContext ctx = buildTimingContext();
        if (ctx == null) {
            return;
        }
        NudgeSettings settings = host.project().getNudgeSettings();
        double beatDelta = NudgeService.beatsFor(settings, ctx, directionMultiplier);
        applyNudge(clips, beatDelta, settings, directionMultiplier);
    }

    /**
     * Nudges every selected audio clip by exactly one sample, independent of
     * the configured {@link NudgeSettings} — drives the {@code Alt+Left/Right}
     * shortcut from Issue 566.
     */
    private void nudgeSelectionBySample(double directionMultiplier) {
        List<AudioClip> clips = resolveNudgeTargets();
        if (clips.isEmpty()) {
            return;
        }
        NudgeService.TimingContext ctx = buildTimingContext();
        if (ctx == null) {
            return;
        }
        double beatDelta = NudgeService.beatsForOneSample(ctx, directionMultiplier);
        applyNudge(clips, beatDelta,
                new NudgeSettings(NudgeUnit.FRAMES, 1.0), directionMultiplier);
    }

    private void applyNudge(List<AudioClip> clips, double beatDelta,
                            NudgeSettings settings, double directionMultiplier) {
        NudgeClipsAction action = NudgeService.buildAction(clips, beatDelta);
        if (action == null) {
            return;
        }
        host.undoManager().execute(action);
        host.refreshArrangementCanvas();
        host.updateUndoRedoState();
        host.markProjectDirty();

        String dir = directionMultiplier >= 0 ? "right" : "left";
        double mag = Math.abs(directionMultiplier);
        String magDesc = mag == 1.0 ? "" : (mag == 10.0 ? "10× " : String.format("%.1f× ", mag));
        host.updateStatusBar(
                String.format("Nudged %d clip(s) %s%s by %s %s",
                        clips.size(), magDesc, dir,
                        formatAmount(settings.amount()),
                        formatUnit(settings.unit(), settings.amount())),
                null);
    }

    /**
     * Resolves the current selection to the list of audio clips to nudge.
     * Priority: explicit clip selection → clips contained in the current
     * time selection. Returns an empty list if nothing is selectable.
     */
    private List<AudioClip> resolveNudgeTargets() {
        SelectionModel sm = host.selectionModel();
        List<ClipboardEntry> selected = sm.getSelectedClips();
        if (!selected.isEmpty()) {
            List<AudioClip> clips = new ArrayList<>(selected.size());
            for (ClipboardEntry entry : selected) {
                clips.add(entry.clip());
            }
            return clips;
        }
        // Fall back to clips contained within the current time selection
        // (time-range shifts its contained clips — Issue 566).
        if (sm.hasSelection()) {
            double s = sm.getStartBeat();
            double e = sm.getEndBeat();
            if (s < e) {
                List<AudioClip> clips = new ArrayList<>();
                for (Track t : host.project().getTracks()) {
                    for (AudioClip c : t.getClips()) {
                        if (c.getStartBeat() >= s && c.getStartBeat() < e) {
                            clips.add(c);
                        }
                    }
                }
                return clips;
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
        var transport = host.project().getTransport();
        double bpm = transport.getTempo();
        double sampleRate = host.project().getFormat().sampleRate();
        double gridStep = host.gridStepBeats();
        double barBeats = transport.getTimeSignatureNumerator() * (4.0
                / Math.max(1, transport.getTimeSignatureDenominator()));
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
        List<ClipboardEntry> selected = host.selectionModel().getSelectedClips();
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
        host.undoManager().execute(action);
        host.selectionModel().clearClipSelection();
        host.refreshArrangementCanvas();
        host.updateUndoRedoState();
        host.updateStatusBar("Deleted " + entries.size() + " clip(s)", null);
        host.markProjectDirty();
    }

    /**
     * Builds the undoable action for a delete/cut, routing through the
     * {@link RippleEditService} when the project's {@link RippleMode} is
     * active. Returns {@code null} if ripple validation rejects the edit;
     * in that case the user has already been notified.
     */
    private UndoableAction buildDeleteAction(
            List<Map.Entry<Track, AudioClip>> entries, String verb) {
        RippleMode mode = host.rippleMode();
        if (mode == RippleMode.OFF) {
            return new CutClipsAction(entries);
        }
        SelectionBounds selection = rippleSelection(entries);
        try {
            return RippleEditService.buildRippleDelete(
                    entries, mode, host.project().getTracks(),
                    selection.start(), selection.end());
        } catch (RippleValidationException e) {
            host.showNotification(NotificationLevel.ERROR,
                    verb + " cancelled — ripple would overlap clips: " + e.getMessage());
            return null;
        }
    }

    private SelectionBounds rippleSelection(List<Map.Entry<Track, AudioClip>> entries) {
        SelectionModel selectionModel = host.selectionModel();
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
        EditorView editorView = host.editorView();
        Track track = editorView.getSelectedTrack();
        if (track == null || track.getClips().isEmpty()) {
            return;
        }
        List<AudioClip> clips = track.getClips();
        host.undoManager().execute(new UndoableAction() {
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
        host.updateUndoRedoState();
        editorView.getWaveformDisplay().refresh();
        host.showNotificationWithUndo(NotificationLevel.SUCCESS,
                "Trimmed: " + track.getName(), () -> host.undoManager().undo());
        host.markProjectDirty();
    }

    void onEditorFadeIn() {
        EditorView editorView = host.editorView();
        Track track = editorView.getSelectedTrack();
        if (track == null || track.getClips().isEmpty()) {
            return;
        }
        List<AudioClip> clips = track.getClips();
        double defaultFadeBeats = 2.0;
        host.undoManager().execute(new UndoableAction() {
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
        host.updateUndoRedoState();
        editorView.getWaveformDisplay().refresh();
        host.showNotificationWithUndo(NotificationLevel.SUCCESS,
                "Fade in applied: " + track.getName(), () -> host.undoManager().undo());
        host.markProjectDirty();
    }

    void onEditorFadeOut() {
        EditorView editorView = host.editorView();
        Track track = editorView.getSelectedTrack();
        if (track == null || track.getClips().isEmpty()) {
            return;
        }
        List<AudioClip> clips = track.getClips();
        double defaultFadeBeats = 2.0;
        host.undoManager().execute(new UndoableAction() {
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
        host.updateUndoRedoState();
        editorView.getWaveformDisplay().refresh();
        host.showNotificationWithUndo(NotificationLevel.SUCCESS,
                "Fade out applied: " + track.getName(), () -> host.undoManager().undo());
        host.markProjectDirty();
    }

    // ── Helpers ────────────────────────���───────────────────────────��─────────

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
