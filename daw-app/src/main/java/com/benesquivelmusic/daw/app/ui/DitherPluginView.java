package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor;
import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor.DitherType;
import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor.NoiseShape;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;

/**
 * JavaFX view for the built-in {@link DitherProcessor}.
 *
 * <p>Provides three dropdowns — bit depth (16 / 20 / 24), dither type
 * (None / RPDF / TPDF / Noise-Shaped), and noise-shaping curve
 * (Flat / Weighted / POW-r 1 / POW-r 2 / POW-r 3) — alongside descriptive
 * text explaining the trade-offs of each option.</p>
 *
 * <p>The view writes parameter changes directly to the underlying
 * {@link DitherProcessor} on the JavaFX application thread; the processor
 * picks them up on its next audio-thread buffer. All setters on
 * {@code DitherProcessor} ({@code setType}, {@code setShape},
 * {@code setTargetBitDepth}) are volatile writes and allocation-free,
 * so they are safe to call from any thread without synchronization.</p>
 */
public final class DitherPluginView extends VBox {

    /** Supported target bit depths per the dither plugin spec. */
    public static final List<Integer> BIT_DEPTHS = List.of(16, 20, 24);

    private final DitherProcessor processor;
    private final Label description;

    /**
     * Creates a new dither plugin view bound to the given processor.
     *
     * @param processor the processor to control; must not be {@code null}
     */
    public DitherPluginView(DitherProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor must not be null");

        setSpacing(10);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_CENTER);
        setStyle("-fx-background-color: #2b2b2b;");

        Label title = new Label("Dither — Bit-Depth Reducer (Mastering Output)");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        ComboBox<Integer> bitDepth = new ComboBox<>();
        bitDepth.getItems().addAll(BIT_DEPTHS);
        bitDepth.setValue(snapBitDepth(processor.getTargetBitDepth()));
        bitDepth.valueProperty().addListener((_, _, v) -> {
            if (v != null) processor.setTargetBitDepth(v);
        });

        ComboBox<DitherType> type = new ComboBox<>();
        type.getItems().addAll(DitherType.values());
        type.setValue(processor.getType());
        type.valueProperty().addListener((_, _, v) -> {
            if (v != null) {
                processor.setType(v);
                updateDescription(v, processor.getShape());
            }
        });

        ComboBox<NoiseShape> shape = new ComboBox<>();
        shape.getItems().addAll(NoiseShape.values());
        shape.setValue(processor.getShape());
        shape.valueProperty().addListener((_, _, v) -> {
            if (v != null) {
                processor.setShape(v);
                updateDescription(processor.getType(), v);
            }
        });

        HBox controls = new HBox(12,
                labelled("Bit Depth", bitDepth),
                labelled("Type",      type),
                labelled("Shape",     shape));
        controls.setAlignment(Pos.CENTER_LEFT);

        description = new Label();
        description.setWrapText(true);
        description.setMaxWidth(560);
        description.setStyle("-fx-text-fill: #bbb; -fx-font-size: 11px;");
        updateDescription(processor.getType(), processor.getShape());

        getChildren().addAll(title, controls, description);
    }

    private static VBox labelled(String text, javafx.scene.Node node) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #ccc;");
        VBox box = new VBox(2, l, node);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Snaps an arbitrary stored bit depth (e.g. 24 from a project file) to a UI-supported value. */
    static int snapBitDepth(int v) {
        if (v <= 18) return 16;
        if (v <= 22) return 20;
        return 24;
    }

    /** Returns the human-readable description for a (type, shape) combination. */
    static String describe(DitherType type, NoiseShape shape) {
        String typePart = switch (type) {
            case NONE -> "No dither — pure truncation. Cheap but produces audible "
                    + "harmonic distortion on quiet signals (reverb tails, fades). "
                    + "Use only for already-integer material or measurement.";
            case RPDF -> "Rectangular PDF dither (1 LSB peak-to-peak). Removes "
                    + "quantization distortion but leaves a small amount of "
                    + "signal-dependent noise modulation.";
            case TPDF -> "Triangular PDF dither (2 LSB peak-to-peak). The AES-recommended "
                    + "default — fully decouples noise from signal and produces a "
                    + "constant, signal-independent noise floor.";
            case NOISE_SHAPED -> "TPDF dither plus an error-feedback noise-shaping filter "
                    + "that pushes quantization noise out of the most audible band "
                    + "(2–6 kHz) at the cost of higher ultrasonic noise.";
        };
        String shapePart = switch (shape) {
            case FLAT     -> "";
            case WEIGHTED -> " — weighted (psychoacoustic, ~14 dB perceived SNR gain "
                    + "in the 2–6 kHz region; best for 44.1/48 kHz 16-bit material).";
            case POWR_1   -> " — POW-r style 1: gentle high-frequency shaping, suited "
                    + "to classical and acoustic material.";
            case POWR_2   -> " — POW-r style 2: moderate shaping, suited to pop / jazz.";
            case POWR_3   -> " — POW-r style 3: aggressive shaping, suited to dense, "
                    + "full-bandwidth masters.";
        };
        return typePart + (type == DitherType.NOISE_SHAPED ? shapePart : "");
    }

    private void updateDescription(DitherType type, NoiseShape shape) {
        description.setText(describe(type, shape));
    }
}
