package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.audio.AudioDeviceManager;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Handles audio and MIDI track creation with device selection dialogs.
 *
 * <p>Extracted from {@code MainController} to separate track creation
 * and device enumeration from the main coordinator.</p>
 */
final class TrackCreationController {

    private static final Logger LOG = Logger.getLogger(TrackCreationController.class.getName());

    /**
     * Story 294 — direct functional deps replace the callback-up {@code Host}
     * (Control Synchronization Design Book §4.2/§9). The swappable project /
     * undo manager / track-strip controller / mixer view are live
     * {@link Supplier}s (a project load is reflected without rebuilding this
     * controller); undo-menu/dirty/status/notification are {@link Runnable}/
     * {@link BiConsumer} sinks. The old {@code updateArrangementPlaceholder}
     * hook is gone — the arrangement placeholder binds {@code ProjectVM.tracks}
     * now (story 293), so the add/undo paths no longer poke it.
     */
    record Deps(
            Supplier<DawProject> project,
            Supplier<UndoManager> undoManager,
            Supplier<TrackStripController> trackStripController,
            Supplier<MixerView> mixerView,
            Supplier<VBox> trackListPanel,
            Runnable updateUndoRedoState,
            Runnable markProjectDirty,
            BiConsumer<String, DawIcon> updateStatusBar,
            BiConsumer<NotificationLevel, String> showNotification) {
    }

    private final Deps deps;
    private final AudioDeviceManager audioDeviceManager;
    private int audioTrackCounter;
    private int midiTrackCounter;

    TrackCreationController(Deps deps, AudioDeviceManager audioDeviceManager) {
        this.deps = deps;
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
        deps.undoManager().get().execute(new UndoableAction() {
            private Track track;
            private HBox trackItem;
            private boolean initialExecute = true;
            @Override public String description() { return "Add Audio Track: " + name; }
            @Override public void execute() {
                if (initialExecute) {
                    track = deps.project().get().createAudioTrack(name);
                    track.setInputDeviceIndex(selectedDevice.index());
                    trackItem = deps.trackStripController().get().addTrackToUI(track);
                    initialExecute = false;
                } else {
                    deps.project().get().addTrack(track);
                    deps.trackListPanel().get().getChildren().add(trackItem);
                }
                EventBusPublisher.publish(new MixerEvent.ChannelAdded(
                        channelIdFor(track, deps.project().get()), Instant.now()));
                deps.mixerView().get().refresh();
            }
            @Override public void undo() {
                deps.project().get().removeTrack(track);
                EventBusPublisher.publish(new MixerEvent.ChannelRemoved(
                        channelIdFor(track, deps.project().get()), Instant.now()));
                deps.trackListPanel().get().getChildren().remove(trackItem);
                audioTrackCounter--;
                deps.mixerView().get().refresh();
            }
        });
        deps.updateUndoRedoState().run();
        deps.updateStatusBar().accept("Added audio track: " + name + " \u2190 " + selectedDevice.name(), DawIcon.INPUT);
        deps.showNotification().accept(NotificationLevel.SUCCESS, "Added audio track: " + name);
        deps.markProjectDirty().run();
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
        deps.undoManager().get().execute(new UndoableAction() {
            private Track track;
            private HBox trackItem;
            private boolean initialExecute = true;
            @Override public String description() { return "Add MIDI Track: " + name; }
            @Override public void execute() {
                if (initialExecute) {
                    track = deps.project().get().createMidiTrack(name);
                    track.setMidiInputDeviceName(selectedMidi.getName());
                    trackItem = deps.trackStripController().get().addTrackToUI(track);
                    initialExecute = false;
                } else {
                    deps.project().get().addTrack(track);
                    deps.trackListPanel().get().getChildren().add(trackItem);
                }
                EventBusPublisher.publish(new MixerEvent.ChannelAdded(
                        channelIdFor(track, deps.project().get()), Instant.now()));
                deps.mixerView().get().refresh();
            }
            @Override public void undo() {
                deps.project().get().removeTrack(track);
                EventBusPublisher.publish(new MixerEvent.ChannelRemoved(
                        channelIdFor(track, deps.project().get()), Instant.now()));
                deps.trackListPanel().get().getChildren().remove(trackItem);
                midiTrackCounter--;
                deps.mixerView().get().refresh();
            }
        });
        deps.updateUndoRedoState().run();
        deps.updateStatusBar().accept("Added MIDI track: " + name + " \u2190 " + selectedMidi.getName(), DawIcon.MUSIC_NOTE);
        deps.showNotification().accept(NotificationLevel.SUCCESS, "Added MIDI track: " + name);
        deps.markProjectDirty().run();
        LOG.fine(() -> "Added MIDI track: " + name + " with input: " + selectedMidi.getName());
    }

    private static UUID channelIdFor(Track track, DawProject project) {
        try {
            return UUID.fromString(track.getId());
        } catch (IllegalArgumentException e) {
            var channel = project.getMixerChannelForTrack(track);
            return channel != null ? channel.getId() : UUID.randomUUID();
        }
    }
}
