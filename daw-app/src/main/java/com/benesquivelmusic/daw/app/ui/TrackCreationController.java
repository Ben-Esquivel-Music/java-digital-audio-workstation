package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.audio.AudioDeviceManager;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Handles audio and MIDI track creation with device selection dialogs.
 *
 * <p>Extracted from {@code MainController} to separate track creation
 * and device enumeration from the main coordinator.</p>
 */
final class TrackCreationController {

    private static final Logger LOG = Logger.getLogger(TrackCreationController.class.getName());

    interface Host {
        DawProject project();
        UndoManager undoManager();
        TrackStripController trackStripController();
        MixerView mixerView();
        javafx.scene.layout.VBox trackListPanel();
        void updateArrangementPlaceholder();
        void updateUndoRedoState();
        void markProjectDirty();
        void updateStatusBar(String text, DawIcon icon);
        void showNotification(NotificationLevel level, String message);
    }

    private final Host host;
    private final AudioDeviceManager audioDeviceManager;
    private int audioTrackCounter;
    private int midiTrackCounter;

    TrackCreationController(Host host, AudioDeviceManager audioDeviceManager) {
        this.host = host;
        this.audioDeviceManager = audioDeviceManager;
    }

    int getAudioTrackCounter() { return audioTrackCounter; }
    int getMidiTrackCounter() { return midiTrackCounter; }
    void setAudioTrackCounter(int count) { this.audioTrackCounter = count; }
    void setMidiTrackCounter(int count) { this.midiTrackCounter = count; }
    void resetCounters() { audioTrackCounter = 0; midiTrackCounter = 0; }

    void onAddAudioTrack() {
        List<AudioDeviceInfo> devices = audioDeviceManager.getAvailableDevices();

        InputPortSelectionDialog dialog = new InputPortSelectionDialog(devices, Track.NO_INPUT_DEVICE);
        Optional<AudioDeviceInfo> selected = dialog.showAndWait();
        if (selected.isEmpty()) {
            return;
        }

        AudioDeviceInfo selectedDevice = selected.get();
        audioTrackCounter++;
        String name = "Audio " + audioTrackCounter;
        host.undoManager().execute(new UndoableAction() {
            private Track track;
            private HBox trackItem;
            private boolean initialExecute = true;
            @Override public String description() { return "Add Audio Track: " + name; }
            @Override public void execute() {
                if (initialExecute) {
                    track = host.project().createAudioTrack(name);
                    track.setInputDeviceIndex(selectedDevice.index());
                    trackItem = host.trackStripController().addTrackToUI(track);
                    initialExecute = false;
                } else {
                    host.project().addTrack(track);
                    host.trackListPanel().getChildren().add(trackItem);
                }
                host.updateArrangementPlaceholder();
                host.mixerView().refresh();
            }
            @Override public void undo() {
                host.project().removeTrack(track);
                host.trackListPanel().getChildren().remove(trackItem);
                audioTrackCounter--;
                host.updateArrangementPlaceholder();
                host.mixerView().refresh();
            }
        });
        host.updateUndoRedoState();
        host.updateStatusBar("Added audio track: " + name + " \u2190 " + selectedDevice.name(), DawIcon.INPUT);
        host.showNotification(NotificationLevel.SUCCESS, "Added audio track: " + name);
        host.markProjectDirty();
        LOG.fine(() -> "Added audio track: " + name + " with input: " + selectedDevice.name());
    }

    void onAddMidiTrack() {
        MidiInputPortSelectionDialog dialog = new MidiInputPortSelectionDialog(null);
        Optional<javax.sound.midi.MidiDevice.Info> selected = dialog.showAndWait();
        if (selected.isEmpty()) {
            return;
        }

        javax.sound.midi.MidiDevice.Info selectedMidi = selected.get();
        midiTrackCounter++;
        String name = "MIDI " + midiTrackCounter;
        host.undoManager().execute(new UndoableAction() {
            private Track track;
            private HBox trackItem;
            private boolean initialExecute = true;
            @Override public String description() { return "Add MIDI Track: " + name; }
            @Override public void execute() {
                if (initialExecute) {
                    track = host.project().createMidiTrack(name);
                    track.setMidiInputDeviceName(selectedMidi.getName());
                    trackItem = host.trackStripController().addTrackToUI(track);
                    initialExecute = false;
                } else {
                    host.project().addTrack(track);
                    host.trackListPanel().getChildren().add(trackItem);
                }
                host.updateArrangementPlaceholder();
                host.mixerView().refresh();
            }
            @Override public void undo() {
                host.project().removeTrack(track);
                host.trackListPanel().getChildren().remove(trackItem);
                midiTrackCounter--;
                host.updateArrangementPlaceholder();
                host.mixerView().refresh();
            }
        });
        host.updateUndoRedoState();
        host.updateStatusBar("Added MIDI track: " + name + " \u2190 " + selectedMidi.getName(), DawIcon.MUSIC_NOTE);
        host.showNotification(NotificationLevel.SUCCESS, "Added MIDI track: " + name);
        host.markProjectDirty();
        LOG.fine(() -> "Added MIDI track: " + name + " with input: " + selectedMidi.getName());
    }
}
