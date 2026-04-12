package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CutClipsAction;
import com.benesquivelmusic.daw.core.audio.DuplicateClipsAction;
import com.benesquivelmusic.daw.core.audio.PasteClipsAction;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        EditorView editorView();
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
        host.undoManager().execute(new CutClipsAction(entries));
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

    void onDeleteSelection() {
        List<ClipboardEntry> selected = host.selectionModel().getSelectedClips();
        if (selected.isEmpty()) {
            return;
        }
        List<Map.Entry<Track, AudioClip>> entries = new ArrayList<>();
        for (ClipboardEntry entry : selected) {
            entries.add(Map.entry(entry.sourceTrack(), entry.clip()));
        }
        host.undoManager().execute(new CutClipsAction(entries));
        host.selectionModel().clearClipSelection();
        host.refreshArrangementCanvas();
        host.updateUndoRedoState();
        host.updateStatusBar("Deleted " + entries.size() + " clip(s)", null);
        host.markProjectDirty();
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
