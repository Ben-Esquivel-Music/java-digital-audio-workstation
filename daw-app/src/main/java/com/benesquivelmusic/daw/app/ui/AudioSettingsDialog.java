package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSize;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Modal dialog for configuring audio engine parameters at runtime.
 *
 * <p>Exposes the backend, input/output device selection, sample rate,
 * buffer size, and bit depth; displays the expected round-trip latency,
 * shows live CPU load from the audio thread, and offers a test tone
 * button so users can verify the configuration before committing.</p>
 *
 * <p>On Apply the dialog persists its values via the supplied
 * {@link SettingsModel} and, when an {@link AudioEngineController} is
 * available, asks it to stop, reconfigure, and restart the live audio
 * engine. The user is warned first that playback will be briefly
 * interrupted.</p>
 */
public final class AudioSettingsDialog extends Dialog<Void> {

    private static final Logger LOG = Logger.getLogger(AudioSettingsDialog.class.getName());

    private static final double HEADER_ICON_SIZE = 18;
    private static final List<Integer> BUFFER_SIZE_OPTIONS =
            List.of(32, 64, 128, 256, 512, 1024, 2048);
    private static final List<Integer> SAMPLE_RATE_OPTIONS =
            List.of(44_100, 48_000, 88_200, 96_000, 176_400, 192_000);
    private static final List<Integer> BIT_DEPTH_OPTIONS = List.of(16, 24, 32);

    private final SettingsModel model;
    private final AudioEngineController controller;

    private final ComboBox<String> backendCombo;
    private final ComboBox<String> inputDeviceCombo;
    private final ComboBox<String> outputDeviceCombo;
    private final ComboBox<Integer> sampleRateCombo;
    private final ComboBox<Integer> bufferSizeCombo;
    private final ComboBox<Integer> bitDepthCombo;
    private final ComboBox<MixPrecision> mixPrecisionCombo;
    private final Label bufferLatencyLabel;
    private final Label sampleRateLatencyLabel;
    private final Label cpuLoadLabel;
    private final Label activeBackendLabel;
    private final Button testToneButton;
    private final Button openControlPanelButton;
    private final Timeline cpuPollTimer;

    /** Most-recently enumerated device list for the active backend selection. */
    private List<AudioDeviceInfo> currentDevices = List.of();

    /** Guards combo-box value changes triggered by refresh, not user edits. */
    private boolean suppressChangeEvents;

    /**
     * Creates a new audio settings dialog.
     *
     * @param model      the settings model (persists user choices)
     * @param controller the audio engine controller, or {@code null} for
     *                   a read-only preview dialog used in tests
     */
    public AudioSettingsDialog(SettingsModel model, AudioEngineController controller) {
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.controller = controller;

        setTitle("Audio Settings");
        setHeaderText("Audio Engine Configuration");
        setGraphic(IconNode.of(DawIcon.HEADPHONES, 24));

        backendCombo = new ComboBox<>();
        inputDeviceCombo = new ComboBox<>();
        outputDeviceCombo = new ComboBox<>();
        sampleRateCombo = new ComboBox<>();
        bufferSizeCombo = new ComboBox<>();
        bitDepthCombo = new ComboBox<>();
        mixPrecisionCombo = new ComboBox<>();

        bufferSizeCombo.getItems().setAll(BUFFER_SIZE_OPTIONS);
        bitDepthCombo.getItems().setAll(BIT_DEPTH_OPTIONS);
        mixPrecisionCombo.getItems().setAll(MixPrecision.values());

        bufferLatencyLabel = new Label();
        sampleRateLatencyLabel = new Label();
        cpuLoadLabel = new Label("CPU: —");
        activeBackendLabel = new Label();
        testToneButton = new Button("Test Tone");
        testToneButton.setGraphic(IconNode.of(DawIcon.PLAY, 12));
        openControlPanelButton = new Button("Open Driver Control Panel");
        openControlPanelButton.setGraphic(IconNode.of(DawIcon.HEADPHONES, 12));
        openControlPanelButton.setVisible(!GraphicsEnvironment.isHeadless());
        openControlPanelButton.setManaged(!GraphicsEnvironment.isHeadless());

        getDialogPane().setContent(buildContent());
        getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(540);

        initializeFromModelAndController();
        wireListeners();

        cpuPollTimer = new Timeline(
                new KeyFrame(Duration.seconds(0.5), _ -> refreshCpuLoad()));
        cpuPollTimer.setCycleCount(Timeline.INDEFINITE);
        setOnShown(_ -> cpuPollTimer.play());
        setOnHidden(_ -> cpuPollTimer.stop());

        DarkThemeHelper.applyTo(this);

        setResultConverter(button -> {
            if (button == ButtonType.APPLY) {
                applyAndReconfigure();
            }
            return null;
        });
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private Node buildContent() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        Label header = new Label("Audio Device & Engine");
        header.setGraphic(IconNode.of(DawIcon.HEADPHONES, HEADER_ICON_SIZE));
        header.setStyle("-fx-font-weight: bold;");
        grid.add(header, 0, 0, 3, 1);
        grid.add(new Separator(), 0, 1, 3, 1);

        int row = 2;
        grid.add(new Label("Active Backend:"), 0, row);
        grid.add(activeBackendLabel, 1, row, 2, 1);
        row++;

        grid.add(new Label("Backend:"), 0, row);
        grid.add(backendCombo, 1, row, 2, 1);
        row++;

        grid.add(new Label("Input Device:"), 0, row);
        grid.add(inputDeviceCombo, 1, row, 2, 1);
        row++;

        grid.add(new Label("Output Device:"), 0, row);
        grid.add(outputDeviceCombo, 1, row);
        grid.add(openControlPanelButton, 2, row);
        row++;

        grid.add(new Label("Sample Rate (Hz):"), 0, row);
        grid.add(sampleRateCombo, 1, row);
        grid.add(sampleRateLatencyLabel, 2, row);
        row++;

        grid.add(new Label("Buffer Size (frames):"), 0, row);
        grid.add(bufferSizeCombo, 1, row);
        grid.add(bufferLatencyLabel, 2, row);
        row++;

        grid.add(new Label("Bit Depth:"), 0, row);
        grid.add(bitDepthCombo, 1, row);
        row++;

        grid.add(new Label("Mix Bus Precision:"), 0, row);
        grid.add(mixPrecisionCombo, 1, row, 2, 1);
        row++;

        HBox buttonRow = new HBox(12, testToneButton, cpuLoadLabel);
        grid.add(new Separator(), 0, row, 3, 1);
        row++;
        grid.add(buttonRow, 0, row, 3, 1);
        row++;

        Label restartHint = new Label(
                "Applying changes briefly interrupts playback while the audio engine restarts.");
        restartHint.setGraphic(IconNode.of(DawIcon.WARNING, 14));
        restartHint.setStyle("-fx-text-fill: #ff9100; -fx-font-size: 10px;");
        restartHint.setWrapText(true);
        grid.add(restartHint, 0, row, 3, 1);

        return grid;
    }

    // ── Initialization ───────────────────────────────────────────────────────

    private void initializeFromModelAndController() {
        suppressChangeEvents = true;
        try {
            List<String> backends = controller != null
                    ? controller.getAvailableBackendNames()
                    : List.of("Java Sound");
            backendCombo.getItems().setAll(backends);

            String preferredBackend = model.getAudioBackend();
            if (preferredBackend.isBlank() && controller != null) {
                preferredBackend = controller.getActiveBackendName();
            }
            backendCombo.setValue(selectOrFirst(backends, preferredBackend));

            activeBackendLabel.setText(controller != null
                    ? controller.getActiveBackendName()
                    : "(no controller attached)");

            sampleRateCombo.getItems().setAll(SAMPLE_RATE_OPTIONS);
            sampleRateCombo.setValue(nearestOption(SAMPLE_RATE_OPTIONS, (int) model.getSampleRate()));

            bufferSizeCombo.setValue(nearestOption(BUFFER_SIZE_OPTIONS, model.getBufferSize()));
            bitDepthCombo.setValue(nearestOption(BIT_DEPTH_OPTIONS, model.getBitDepth()));
            mixPrecisionCombo.setValue(model.getMixPrecision());

            refreshDevicesForBackend(backendCombo.getValue());
            refreshControlPanelButton();

            refreshLatencyLabels();
            refreshCpuLoad();
        } finally {
            suppressChangeEvents = false;
        }
    }

    private void wireListeners() {
        ChangeListener<Object> recalc = (_, _, _) -> {
            if (!suppressChangeEvents) {
                refreshLatencyLabels();
            }
        };
        sampleRateCombo.valueProperty().addListener(recalc);
        bufferSizeCombo.valueProperty().addListener(recalc);

        backendCombo.valueProperty().addListener((_, _, newVal) -> {
            if (suppressChangeEvents || newVal == null) {
                return;
            }
            refreshDevicesForBackend(newVal);
            refreshControlPanelButton();
            refreshLatencyLabels();
        });

        outputDeviceCombo.valueProperty().addListener((_, _, newVal) -> {
            if (suppressChangeEvents || newVal == null) {
                return;
            }
            filterSampleRatesForDevice(newVal);
        });

        testToneButton.setOnAction(_ -> onTestTone());
        openControlPanelButton.setOnAction(_ -> onOpenControlPanel());
    }

    // ── Behaviour ────────────────────────────────────────────────────────────

    private void refreshDevicesForBackend(String backendName) {
        if (controller == null || backendName == null) {
            currentDevices = List.of();
            inputDeviceCombo.getItems().setAll("(default)");
            outputDeviceCombo.getItems().setAll("(default)");
            inputDeviceCombo.setValue("(default)");
            outputDeviceCombo.setValue("(default)");
            return;
        }

        currentDevices = controller.listDevices(backendName);
        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        inputs.add("(default)");
        outputs.add("(default)");
        for (AudioDeviceInfo info : currentDevices) {
            if (info.supportsInput()) {
                inputs.add(info.name());
            }
            if (info.supportsOutput()) {
                outputs.add(info.name());
            }
        }

        suppressChangeEvents = true;
        try {
            inputDeviceCombo.getItems().setAll(inputs);
            outputDeviceCombo.getItems().setAll(outputs);
            inputDeviceCombo.setValue(selectOrFirst(inputs, model.getAudioInputDevice()));
            outputDeviceCombo.setValue(selectOrFirst(outputs, model.getAudioOutputDevice()));
        } finally {
            suppressChangeEvents = false;
        }
        filterSampleRatesForDevice(outputDeviceCombo.getValue());
    }

    private void filterSampleRatesForDevice(String deviceName) {
        List<Integer> previousItems = new ArrayList<>(sampleRateCombo.getItems());
        Integer previousValue = sampleRateCombo.getValue();

        List<Integer> filtered;
        AudioDeviceInfo info = findDeviceByName(deviceName);
        if (info == null || info.supportedSampleRates() == null || info.supportedSampleRates().isEmpty()) {
            filtered = SAMPLE_RATE_OPTIONS;
        } else {
            filtered = new ArrayList<>();
            for (SampleRate rate : info.supportedSampleRates()) {
                if (SAMPLE_RATE_OPTIONS.contains(rate.getHz())) {
                    filtered.add(rate.getHz());
                }
            }
            if (filtered.isEmpty()) {
                filtered = SAMPLE_RATE_OPTIONS;
            }
        }

        if (filtered.equals(previousItems)) {
            return;
        }
        suppressChangeEvents = true;
        try {
            sampleRateCombo.getItems().setAll(filtered);
            sampleRateCombo.setValue(previousValue != null && filtered.contains(previousValue)
                    ? previousValue
                    : filtered.getFirst());
        } finally {
            suppressChangeEvents = false;
        }
        refreshLatencyLabels();
    }

    private AudioDeviceInfo findDeviceByName(String name) {
        if (name == null || name.isBlank() || "(default)".equals(name)) {
            return null;
        }
        for (AudioDeviceInfo info : currentDevices) {
            if (name.equals(info.name())) {
                return info;
            }
        }
        return null;
    }

    private void refreshLatencyLabels() {
        Integer bufferFrames = bufferSizeCombo.getValue();
        Integer sampleRate = sampleRateCombo.getValue();
        if (bufferFrames == null || sampleRate == null || sampleRate <= 0) {
            bufferLatencyLabel.setText("—");
            sampleRateLatencyLabel.setText("");
            return;
        }
        double bufferLatencyMs = (bufferFrames / (double) sampleRate) * 1000.0;
        double extraHardwareLatency = extractHardwareLatency(outputDeviceCombo.getValue());
        double totalLatencyMs = (bufferLatencyMs * 2) + extraHardwareLatency;
        bufferLatencyLabel.setText(String.format("%.1f ms round-trip (%.1f ms buffer)",
                totalLatencyMs, bufferLatencyMs));
        sampleRateLatencyLabel.setText(String.format("%.1f ms / buffer", bufferLatencyMs));
    }

    private double extractHardwareLatency(String outputDeviceName) {
        AudioDeviceInfo info = findDeviceByName(outputDeviceName);
        if (info == null) {
            return 0.0;
        }
        return info.defaultLowOutputLatencyMs();
    }

    private void refreshCpuLoad() {
        if (controller == null) {
            cpuLoadLabel.setText("CPU: —");
            return;
        }
        double load = controller.getCpuLoadPercent();
        if (load < 0) {
            cpuLoadLabel.setText("CPU: —");
        } else {
            cpuLoadLabel.setText(String.format("CPU: %.1f%%", load));
        }
    }

    private void onTestTone() {
        if (controller == null) {
            return;
        }
        String device = outputDeviceCombo.getValue();
        try {
            controller.playTestTone("(default)".equals(device) ? "" : device);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Test tone playback failed", e);
            showError("Test Tone Failed", e.getMessage());
        }
    }

    private void refreshControlPanelButton() {
        Optional<Runnable> panel = controller == null
                ? Optional.empty()
                : controller.openControlPanel();
        boolean enabled = panel.isPresent();
        openControlPanelButton.setDisable(!enabled);
        openControlPanelButton.setTooltip(new Tooltip(enabled
                ? "Launches the active driver's native control panel "
                + "(USB streaming mode, routing matrix, vendor mixer)."
                : "The active backend has no native control panel."));
    }

    /**
     * Launches the active backend's native driver control panel on a
     * non-audio thread, then re-queries device capabilities so the
     * dialog reflects any change the user made there.
     */
    private void onOpenControlPanel() {
        if (controller == null) {
            return;
        }
        Optional<Runnable> action = controller.openControlPanel();
        if (action.isEmpty()) {
            return;
        }
        Runnable runnable = action.get();
        // Run on a background virtual thread so blocking native UI calls
        // never stall the JavaFX application thread or the audio render
        // callback. After the panel closes (the runnable returns), refresh
        // device capabilities on the FX thread.
        Thread.ofVirtual().name("audio-control-panel").start(() -> {
            try {
                runnable.run();
                Platform.runLater(() ->
                        refreshDevicesForBackend(backendCombo.getValue()));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Driver control panel launch failed", e);
                showError("Driver Control Panel Failed",
                        "Could not open the driver control panel: "
                                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
    }

    private void applyAndReconfigure() {
        Integer sampleRate = sampleRateCombo.getValue();
        Integer bufferFrames = bufferSizeCombo.getValue();
        Integer bitDepth = bitDepthCombo.getValue();
        if (sampleRate == null || bufferFrames == null || bitDepth == null) {
            return;
        }

        // Persist user choices first so a crash in the reconfigure does not lose them
        model.setSampleRate(sampleRate);
        model.setBufferSize(bufferFrames);
        model.setBitDepth(bitDepth);
        MixPrecision mixPrecision = mixPrecisionCombo.getValue();
        if (mixPrecision != null) {
            model.setMixPrecision(mixPrecision);
        }
        String backend = backendCombo.getValue();
        if (backend != null) {
            model.setAudioBackend(backend);
        }
        String inputDevice = inputDeviceCombo.getValue();
        if (inputDevice != null) {
            model.setAudioInputDevice("(default)".equals(inputDevice) ? "" : inputDevice);
        }
        String outputDevice = outputDeviceCombo.getValue();
        if (outputDevice != null) {
            model.setAudioOutputDevice("(default)".equals(outputDevice) ? "" : outputDevice);
        }

        if (controller == null) {
            return;
        }

        // Apply mix precision directly — it does not require an engine
        // restart so it is applied outside the Request/applyConfiguration
        // path which stops and restarts the audio stream.
        if (mixPrecision != null) {
            controller.applyMixPrecision(mixPrecision);
        }

        AudioEngineController.Request request = new AudioEngineController.Request(
                backend == null ? controller.getActiveBackendName() : backend,
                model.getAudioInputDevice(),
                model.getAudioOutputDevice(),
                SampleRate.fromHz(sampleRate),
                BufferSize.fromFrames(bufferFrames),
                bitDepth);

        try {
            controller.applyConfiguration(request);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to apply audio configuration", e);
            showError("Audio Configuration Failed",
                    "Could not apply new audio settings: " + e.getMessage());
        }
    }

    private void showError(String header, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(AlertType.ERROR);
            alert.setTitle("Audio Settings");
            alert.setHeaderText(header);
            alert.setContentText(content);
            DarkThemeHelper.applyTo(alert);
            alert.showAndWait();
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static <T> T selectOrFirst(List<T> options, T preferred) {
        if (options.isEmpty()) {
            return null;
        }
        if (preferred != null && options.contains(preferred)) {
            return preferred;
        }
        return options.getFirst();
    }

    private static Integer nearestOption(List<Integer> options, int target) {
        if (options.contains(target)) {
            return target;
        }
        int best = options.getFirst();
        int bestDist = Math.abs(best - target);
        for (Integer opt : options) {
            int dist = Math.abs(opt - target);
            if (dist < bestDist) {
                best = opt;
                bestDist = dist;
            }
        }
        return best;
    }

    // ── Test hooks ───────────────────────────────────────────────────────────

    ComboBox<String> getBackendCombo() {
        return backendCombo;
    }

    ComboBox<Integer> getBufferSizeCombo() {
        return bufferSizeCombo;
    }

    ComboBox<Integer> getSampleRateCombo() {
        return sampleRateCombo;
    }

    ComboBox<String> getInputDeviceCombo() {
        return inputDeviceCombo;
    }

    ComboBox<String> getOutputDeviceCombo() {
        return outputDeviceCombo;
    }

    Label getBufferLatencyLabel() {
        return bufferLatencyLabel;
    }

    Label getCpuLoadLabel() {
        return cpuLoadLabel;
    }

    /** Test hook — applies the current dialog state to the model & controller. */
    void applyNow() {
        applyAndReconfigure();
    }

    /** Test hook — fires the test-tone button action. */
    void fireTestTone() {
        testToneButton.fire();
    }

    /** Test hook — returns the "Open Driver Control Panel" button. */
    Button getOpenControlPanelButton() {
        return openControlPanelButton;
    }

    /**
     * Test hook — synchronously invokes the active backend's control
     * panel runnable on the calling thread (no virtual thread, no
     * dialogs) and refreshes device capabilities, so tests can assert
     * the re-query happened. Returns {@code true} if a runnable was
     * available and ran, {@code false} otherwise.
     */
    boolean fireOpenControlPanelSync() {
        if (controller == null) {
            return false;
        }
        Optional<Runnable> action = controller.openControlPanel();
        if (action.isEmpty()) {
            return false;
        }
        action.get().run();
        refreshDevicesForBackend(backendCombo.getValue());
        return true;
    }

    /** Test hook — read-only snapshot of the filtered sample rate options. */
    List<Integer> getFilteredSampleRates() {
        return Collections.unmodifiableList(sampleRateCombo.getItems());
    }
}
