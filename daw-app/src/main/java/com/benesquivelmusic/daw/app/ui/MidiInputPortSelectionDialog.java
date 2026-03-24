package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Modal dialog for selecting a MIDI input port when creating or
 * configuring a MIDI track.
 *
 * <p>Enumerates MIDI input devices available through
 * {@link javax.sound.midi.MidiSystem} and presents them in a list.
 * The user selects a device and confirms with OK, or cancels to abort.</p>
 */
public final class MidiInputPortSelectionDialog extends Dialog<MidiDevice.Info> {

    private static final Logger LOG = Logger.getLogger(MidiInputPortSelectionDialog.class.getName());
    private static final double HEADER_ICON_SIZE = 24;

    private final ListView<MidiDevice.Info> deviceListView;

    /**
     * Creates a new MIDI input port selection dialog.
     *
     * @param preselectedName the device name to pre-select, or {@code null} for none
     */
    public MidiInputPortSelectionDialog(String preselectedName) {
        setTitle("Select MIDI Input");
        setHeaderText("Choose a MIDI input device for this track");
        setGraphic(IconNode.of(DawIcon.MIDI, HEADER_ICON_SIZE));

        List<MidiDevice.Info> midiInputs = enumerateMidiInputDevices();

        deviceListView = new ListView<>();
        deviceListView.getItems().addAll(midiInputs);
        deviceListView.setPrefHeight(240);
        deviceListView.setPrefWidth(520);
        deviceListView.setCellFactory(_ -> new MidiDeviceCell());

        // Pre-select the device matching the given name
        if (preselectedName != null) {
            midiInputs.stream()
                    .filter(d -> d.getName().equals(preselectedName))
                    .findFirst()
                    .ifPresent(deviceListView.getSelectionModel()::select);
        }

        Label infoLabel = new Label("Select a MIDI input device and click OK");
        infoLabel.setGraphic(IconNode.of(DawIcon.INFO, 14));
        infoLabel.setStyle("-fx-text-fill: #808080; -fx-font-size: 11px;");

        VBox content = new VBox(8, deviceListView, infoLabel);
        content.setPadding(new Insets(16));

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                return deviceListView.getSelectionModel().getSelectedItem();
            }
            return null;
        });
    }

    /**
     * Shows the dialog and returns the selected MIDI device info, if any.
     *
     * @return an {@link Optional} containing the selected device, or empty if cancelled
     */
    public Optional<MidiDevice.Info> showAndGetResult() {
        return showAndWait();
    }

    /**
     * Enumerates MIDI input devices available on the system.
     *
     * <p>A device is considered an input device if it has at least one
     * transmitter (i.e., it can send MIDI data).</p>
     *
     * @return a list of MIDI input device info objects
     */
    static List<MidiDevice.Info> enumerateMidiInputDevices() {
        List<MidiDevice.Info> inputs = new ArrayList<>();
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            try {
                MidiDevice device = MidiSystem.getMidiDevice(info);
                if (device.getMaxTransmitters() != 0) {
                    inputs.add(info);
                }
            } catch (MidiUnavailableException e) {
                LOG.log(Level.FINE, "Skipping unavailable MIDI device: " + info.getName(), e);
            }
        }
        return inputs;
    }

    /**
     * Custom list cell that displays MIDI device details.
     */
    private static final class MidiDeviceCell extends ListCell<MidiDevice.Info> {
        @Override
        protected void updateItem(MidiDevice.Info info, boolean empty) {
            super.updateItem(info, empty);
            if (empty || info == null) {
                setText(null);
                setGraphic(null);
            } else {
                String text = String.format("%s — %s | %s",
                        info.getName(),
                        info.getVendor(),
                        info.getDescription());
                setText(text);
                setGraphic(IconNode.of(DawIcon.MIDI_CABLE, 14));
            }
        }
    }
}
