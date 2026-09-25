package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.GainStagingProcessor;
import com.benesquivelmusic.daw.core.dsp.LimiterProcessor;
import com.benesquivelmusic.daw.core.dsp.ParametricEqProcessor;
import com.benesquivelmusic.daw.core.dsp.StereoImagerProcessor;
import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Parameter controls for the processors that the engine's mastering stages actually own. */
final class MasteringStageControls extends VBox implements AutoCloseable {
    private final MasteringChain chain;
    private final java.util.List<MasteringChain.ParameterControl> parameters = new ArrayList<>();

    private MasteringStageControls(MasteringChain chain) {
        super(3);
        this.chain = chain;
        setMaxWidth(128);
    }

    static MasteringStageControls create(MasteringChain chain, MasteringChain.Stage stage, double sampleRate) {
        var controls = new MasteringStageControls(chain);
        // Type patterns (JEP 441, final in Java 21) keep each DSP control binding explicit.
        switch (stage.getProcessor()) {
            case GainStagingProcessor gain ->
                    controls.slider("Gain", "dB", -24, 24, gain.getGainDb(), gain::setGainDb);
            case CompressorProcessor compressor -> {
                controls.slider("Threshold", "dB", -60, 0, compressor.getThresholdDb(), compressor::setThresholdDb);
                controls.slider("Ratio", ":1", 1, 20, compressor.getRatio(), compressor::setRatio);
                controls.slider("Attack", "ms", 0.1, 100, compressor.getAttackMs(), compressor::setAttackMs);
                controls.slider("Release", "ms", 1, 1000, compressor.getReleaseMs(), compressor::setReleaseMs);
                controls.slider("Knee", "dB", 0, 24, compressor.getKneeDb(), compressor::setKneeDb);
                controls.slider("Makeup", "dB", -12, 24, compressor.getMakeupGainDb(), compressor::setMakeupGainDb);
            }
            case LimiterProcessor limiter -> {
                controls.slider("Ceiling", "dB", -24, 0, limiter.getCeilingDb(), limiter::setCeilingDb);
                controls.slider("Attack", "ms", 0.01, 20, limiter.getAttackMs(), limiter::setAttackMs);
                controls.slider("Release", "ms", 1, 1000, limiter.getReleaseMs(), limiter::setReleaseMs);
            }
            case StereoImagerProcessor imager ->
                    controls.slider("Width", "", 0, 2, imager.getWidth(), imager::setWidth);
            case ParametricEqProcessor eq -> controls.eqBands(stage, eq, sampleRate);
            case DitherProcessor dither -> {
                controls.choice("Bit depth", new Integer[]{16, 20, 24, 32},
                        dither.getTargetBitDepth(), dither::setTargetBitDepth);
                controls.choice("Dither", DitherProcessor.DitherType.values(), dither.getType(), dither::setType);
                controls.choice("Noise shape", DitherProcessor.NoiseShape.values(), dither.getShape(), dither::setShape);
            }
            default -> { }
        }
        return controls;
    }

    private void eqBands(MasteringChain.Stage stage, ParametricEqProcessor eq, double sampleRate) {
        var bands = new ArrayList<>(eq.getBands());
        for (int index = 0; index < eq.getBands().size(); index++) {
            int bandIndex = index;
            var config = eq.getBands().get(index);
            // The control-side copy composes consecutive gestures before the next audio block.
            var pending = new ParametricEqProcessor.BandConfig[]{config};
            slider("Frequency " + (index + 1), "Hz", 20, Math.min(20_000, sampleRate * 0.49),
                    config.frequency(), value -> {
                        var current = pending[0];
                        var next = new ParametricEqProcessor.BandConfig(current.type(), value,
                                current.q(), current.gainDb(), current.enabled());
                        pending[0] = next;
                        bands.set(bandIndex, next);
                        replaceEq(stage, eq, bands, sampleRate);
                    }, MasteringStageControls::applyImmediately);
            slider("Q " + (index + 1), "", 0.1, 10, config.q(), value -> {
                var current = pending[0];
                var next = new ParametricEqProcessor.BandConfig(current.type(), current.frequency(),
                        value, current.gainDb(), current.enabled());
                pending[0] = next;
                bands.set(bandIndex, next);
                replaceEq(stage, eq, bands, sampleRate);
            }, MasteringStageControls::applyImmediately);
            if (config.type() != com.benesquivelmusic.daw.core.dsp.BiquadFilter.FilterType.HIGH_PASS) {
                slider("Gain " + (index + 1), "dB", -12, 12, config.gainDb(), value -> {
                    var current = pending[0];
                    var next = new ParametricEqProcessor.BandConfig(current.type(), current.frequency(),
                            current.q(), value, current.enabled());
                    pending[0] = next;
                    bands.set(bandIndex, next);
                    replaceEq(stage, eq, bands, sampleRate);
                }, MasteringStageControls::applyImmediately);
            }
        }
    }

    private static void replaceEq(MasteringChain.Stage stage, ParametricEqProcessor original,
            java.util.List<ParametricEqProcessor.BandConfig> bands, double sampleRate) {
        var replacement = new ParametricEqProcessor(original.getInputChannelCount(), sampleRate);
        bands.forEach(replacement::addBand);
        original.getMidBands().forEach(replacement::addMidBand);
        original.getSideBands().forEach(replacement::addSideBand);
        replacement.setFirOrder(original.getFirOrder());
        replacement.setFilterMode(original.getFilterMode());
        replacement.setProcessingMode(original.getProcessingMode());
        stage.setProcessor(replacement);
    }

    private static void applyImmediately(Runnable update) {
        update.run();
    }

    private void slider(String name, String unit, double min, double max, double value, DoubleConsumer setter) {
        slider(name, unit, min, max, value, setter, newParameterControl()::submit);
    }

    private void slider(String name, String unit, double min, double max, double value,
            DoubleConsumer setter, Consumer<Runnable> dispatch) {
        var label = new Label(format(name, value, unit));
        var slider = new Slider(Math.min(min, value), Math.max(max, value), value);
        slider.setAccessibleText(name);
        slider.setTooltip(new Tooltip(name));
        slider.valueProperty().addListener((_, _, next) -> {
            double parameter = next.doubleValue();
            dispatch.accept(() -> setter.accept(parameter));
            label.setText(format(name, parameter, unit));
        });
        getChildren().addAll(label, slider);
    }

    private <T> void choice(String name, T[] options, T value, Consumer<T> setter) {
        var choice = new ComboBox<T>();
        choice.getItems().addAll(options);
        choice.setValue(value);
        choice.setMaxWidth(128);
        choice.setAccessibleText(name);
        var parameter = newParameterControl();
        choice.valueProperty().addListener((_, _, next) -> parameter.submit(() -> setter.accept(next)));
        getChildren().addAll(new Label(name), choice);
    }

    private MasteringChain.ParameterControl newParameterControl() {
        var parameter = chain.createParameterControl();
        parameters.add(parameter);
        return parameter;
    }

    @Override public void close() {
        parameters.forEach(MasteringChain.ParameterControl::close);
        parameters.clear();
    }

    private static String format(String name, double value, String unit) {
        return String.format(Locale.ROOT, "%s: %.1f %s", name, value, unit);
    }
}
