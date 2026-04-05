package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.sdk.analysis.WindowType;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;
import com.benesquivelmusic.daw.sdk.visualization.StereoMode;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.function.Consumer;

/**
 * Standalone floating window wrapping a {@link SpectrumDisplay}.
 *
 * <p>Provides the same spectrum analyzer visualization as the embedded
 * tile, but in a freely movable and resizable window. This allows
 * engineers to position the spectrum analyzer anywhere on screen while
 * working in the arrangement or mixer view.</p>
 *
 * <p>Includes a toolbar with controls for FFT size, window function,
 * frequency scale mode, and average trace toggle.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * SpectrumDisplayWindow window = new SpectrumDisplayWindow();
 * window.show();
 * window.updateSpectrum(spectrumData);
 * }</pre>
 */
public final class SpectrumDisplayWindow {

    private static final double DEFAULT_WIDTH = 640;
    private static final double DEFAULT_HEIGHT = 400;

    private final Stage stage;
    private final SpectrumDisplay display;
    private final ComboBox<String> fftSizeCombo;
    private final ComboBox<String> windowTypeCombo;
    private final ComboBox<String> scaleCombo;
    private final CheckBox avgTraceCheck;

    private Consumer<Integer> onFftSizeChanged;
    private Consumer<WindowType> onWindowTypeChanged;

    /**
     * Creates a new floating spectrum display window with the specified
     * number of display bars.
     *
     * @param displayBars the number of visible frequency bars
     */
    public SpectrumDisplayWindow(int displayBars) {
        display = new SpectrumDisplay(displayBars);

        // FFT size selector
        fftSizeCombo = new ComboBox<>();
        fftSizeCombo.getItems().addAll("1024", "2048", "4096", "8192");
        fftSizeCombo.setValue("4096");
        fftSizeCombo.setOnAction(_ -> {
            if (onFftSizeChanged != null) {
                onFftSizeChanged.accept(Integer.parseInt(fftSizeCombo.getValue()));
            }
        });

        // Window function selector
        windowTypeCombo = new ComboBox<>();
        for (WindowType wt : WindowType.values()) {
            windowTypeCombo.getItems().add(wt.displayName());
        }
        windowTypeCombo.setValue(WindowType.HANN.displayName());
        windowTypeCombo.setOnAction(_ -> {
            if (onWindowTypeChanged != null) {
                for (WindowType wt : WindowType.values()) {
                    if (wt.displayName().equals(windowTypeCombo.getValue())) {
                        onWindowTypeChanged.accept(wt);
                        break;
                    }
                }
            }
        });

        // Frequency scale selector
        scaleCombo = new ComboBox<>();
        scaleCombo.getItems().addAll("Logarithmic", "Linear");
        scaleCombo.setValue("Logarithmic");
        scaleCombo.setOnAction(_ -> display.setLogarithmicScale(
                "Logarithmic".equals(scaleCombo.getValue())));

        // Average trace checkbox
        avgTraceCheck = new CheckBox("Avg Trace");
        avgTraceCheck.setStyle("-fx-text-fill: #cccccc;");
        avgTraceCheck.setOnAction(_ -> display.setAverageTraceEnabled(avgTraceCheck.isSelected()));

        Label fftLabel = createToolbarLabel("FFT:");
        Label windowLabel = createToolbarLabel("Window:");
        Label scaleLabel = createToolbarLabel("Scale:");

        HBox toolbar = new HBox(6,
                fftLabel, fftSizeCombo,
                windowLabel, windowTypeCombo,
                scaleLabel, scaleCombo,
                avgTraceCheck);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 8, 4, 8));
        toolbar.setStyle("-fx-background-color: #1a1a2e;");

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(display);

        stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Spectrum Analyzer");

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.setFill(Color.web("#0d0d1a"));
        DarkThemeHelper.applyTo(scene);
        stage.setScene(scene);
        stage.setMinWidth(400);
        stage.setMinHeight(280);
    }

    /**
     * Creates a new floating spectrum display window with 64 bars.
     */
    public SpectrumDisplayWindow() {
        this(64);
    }

    /**
     * Sets a callback invoked when the user changes the FFT size.
     *
     * @param listener the callback receiving the new FFT size
     */
    public void setOnFftSizeChanged(Consumer<Integer> listener) {
        this.onFftSizeChanged = listener;
    }

    /**
     * Sets a callback invoked when the user changes the window function.
     *
     * @param listener the callback receiving the new window type
     */
    public void setOnWindowTypeChanged(Consumer<WindowType> listener) {
        this.onWindowTypeChanged = listener;
    }

    /**
     * Shows the floating window.
     */
    public void show() {
        stage.show();
        stage.toFront();
    }

    /**
     * Hides the floating window.
     */
    public void hide() {
        stage.hide();
    }

    /**
     * Returns whether the floating window is currently showing.
     *
     * @return {@code true} if visible
     */
    public boolean isShowing() {
        return stage.isShowing();
    }

    /**
     * Updates the display with new spectrum data.
     *
     * @param data the latest spectrum snapshot
     */
    public void updateSpectrum(SpectrumData data) {
        display.updateSpectrum(data);
    }

    /**
     * Sets the pre-EQ spectrum data for overlay display.
     *
     * @param preEqData the pre-EQ spectrum snapshot, or {@code null} to clear
     */
    public void setPreEqData(SpectrumData preEqData) {
        display.setPreEqData(preEqData);
    }

    /**
     * Sets the stereo display mode.
     *
     * @param mode the stereo mode
     */
    public void setStereoMode(StereoMode mode) {
        display.setStereoMode(mode);
    }

    /**
     * Sets the right channel spectrum data for stereo display.
     *
     * @param rightData the right channel spectrum snapshot
     */
    public void setRightChannelData(SpectrumData rightData) {
        display.setRightChannelData(rightData);
    }

    /**
     * Returns the underlying {@link SpectrumDisplay} component.
     *
     * @return the spectrum display
     */
    public SpectrumDisplay getDisplay() {
        return display;
    }

    /**
     * Returns the underlying {@link Stage}.
     *
     * @return the stage
     */
    public Stage getStage() {
        return stage;
    }

    private static Label createToolbarLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 10px;");
        return label;
    }
}
