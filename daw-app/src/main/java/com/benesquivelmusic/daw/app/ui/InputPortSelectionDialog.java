package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

/**
 * Modal dialog for selecting an audio input port when creating or
 * configuring an audio track.
 *
 * <p>Lists all available audio input devices obtained from the audio
 * backend, showing device name, host API, channel count, sample rate,
 * and input latency. The user selects a device and confirms with OK,
 * or cancels to abort.</p>
 */
public final class InputPortSelectionDialog extends Dialog<AudioDeviceInfo> {

    private static final double HEADER_ICON_SIZE = 24;

    private final ListView<AudioDeviceInfo> deviceListView;

    /**
     * Creates a new input port selection dialog.
     *
     * @param devices       all available audio devices (filtered to inputs internally)
     * @param preselectedIndex the device index to pre-select, or {@code -1} for none
     */
    public InputPortSelectionDialog(List<AudioDeviceInfo> devices, int preselectedIndex) {
        setTitle("Select Audio Input");
        setHeaderText("Choose an audio input device for this track");
        setGraphic(IconNode.of(DawIcon.INPUT, HEADER_ICON_SIZE));

        List<AudioDeviceInfo> inputDevices = devices.stream()
                .filter(AudioDeviceInfo::supportsInput)
                .toList();

        deviceListView = new ListView<>();
        deviceListView.getItems().addAll(inputDevices);
        deviceListView.setPrefHeight(240);
        deviceListView.setPrefWidth(520);
        deviceListView.setCellFactory(_ -> new AudioDeviceCell());

        // Pre-select the device matching the given index
        if (preselectedIndex >= 0) {
            inputDevices.stream()
                    .filter(d -> d.index() == preselectedIndex)
                    .findFirst()
                    .ifPresent(deviceListView.getSelectionModel()::select);
        }

        Label infoLabel = new Label("Select an input device and click OK");
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
     * Shows the dialog and returns the selected device info, if any.
     *
     * @return an {@link Optional} containing the selected device, or empty if cancelled
     */
    public Optional<AudioDeviceInfo> showAndGetResult() {
        return showAndWait();
    }

    /**
     * Custom list cell that displays audio device details.
     */
    private static final class AudioDeviceCell extends ListCell<AudioDeviceInfo> {
        @Override
        protected void updateItem(AudioDeviceInfo device, boolean empty) {
            super.updateItem(device, empty);
            if (empty || device == null) {
                setText(null);
                setGraphic(null);
            } else {
                String text = String.format("%s — %s | %d ch | %.0f Hz | %.1f ms",
                        device.name(),
                        device.hostApi(),
                        device.maxInputChannels(),
                        device.defaultSampleRate(),
                        device.defaultLowInputLatencyMs());
                setText(text);
                setGraphic(IconNode.of(DawIcon.MICROPHONE, 14));
            }
        }
    }
}
