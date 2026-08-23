package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Modal dialog for selecting an audio input port when creating or
 * configuring an audio track.
 *
 * <p>Lists all available audio input devices obtained from the audio
 * backend, showing device name, host API, channel count, sample rate,
 * and input latency. The user selects a device and confirms with OK,
 * or cancels to abort.</p>
 */
@com.benesquivelmusic.daw.app.ui.dialogs.LegacyDialog(
        "migrate to DawgDialog — story 276 follow-up")
public final class InputPortSelectionDialog extends Dialog<AudioDeviceInfo> {

    private static final double HEADER_ICON_SIZE = 24;

    /**
     * Resource bundle for localized strings (Skill §14) — {@link Locale#ROOT}
     * to match the codebase-wide convention (see {@code SettingsDialog},
     * {@code BrowserPanel}, {@code DawgDialog}).
     */
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

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

        ThemeManager.getDefault().applyTo(getDialogPane());

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
     * The channel-count fragment of a device row. A device whose count is
     * {@link AudioDeviceInfo#CHANNEL_COUNT_UNKNOWN} — an enumerated ASIO
     * driver the host has not loaded — says so, rather than rendering the
     * sentinel as the nonsense "-1 ch" (story 316 review).
     *
     * @param device the device being rendered
     * @return the text to show in the channel column
     */
    static String channelSummary(AudioDeviceInfo device) {
        return device.hasKnownInputChannelCount()
                ? device.maxInputChannels() + " ch"
                : "channel count unknown until opened";
    }

    /**
     * The sample-rate fragment of a device row (story 316 re-review).
     *
     * <p>{@link AudioDeviceInfo#unprobed(int, String, String)} reports
     * {@code 0.0} here because the record has no unknown sentinel for a
     * sample rate, and the row used to print that straight through as
     * "0 Hz" — a fabricated capability for a driver nobody has loaded. A
     * rate that is not positive is not a rate, so it renders as
     * {@link #unknownValue()} instead of a number.</p>
     *
     * @param device the device being rendered
     * @return the text to show in the sample-rate column
     */
    static String sampleRateSummary(AudioDeviceInfo device) {
        double hz = device.defaultSampleRate();
        return hz > 0 ? String.format(Locale.ROOT, "%.0f Hz", hz) : unknownValue();
    }

    /**
     * The input-latency fragment of a device row (story 316 re-review) —
     * same rule as {@link #sampleRateSummary(AudioDeviceInfo)}: an unprobed
     * driver reports {@code 0.0} ms, and "0.0 ms" is a claim no audio
     * interface can honour.
     *
     * @param device the device being rendered
     * @return the text to show in the latency column
     */
    static String inputLatencySummary(AudioDeviceInfo device) {
        double ms = device.defaultLowInputLatencyMs();
        return ms > 0 ? String.format(Locale.ROOT, "%.1f ms", ms) : unknownValue();
    }

    /**
     * How this codebase renders a value it does not have: the em dash behind
     * {@code audio.utility.unavailable}, which the Settings audio utility
     * panel already shows for an unavailable backend or latency. Reused
     * rather than forked into a second token so the two surfaces cannot
     * drift apart.
     *
     * @return the shared "no value" token
     */
    static String unknownValue() {
        try {
            return MESSAGES.getString("audio.utility.unavailable");
        } catch (MissingResourceException e) {
            return "audio.utility.unavailable";
        }
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
                String text = String.format(Locale.ROOT, "%s — %s | %s | %s | %s",
                        device.name(),
                        device.hostApi(),
                        channelSummary(device),
                        sampleRateSummary(device),
                        inputLatencySummary(device));
                setText(text);
                setGraphic(IconNode.of(DawIcon.MICROPHONE, 14));
            }
        }
    }
}
