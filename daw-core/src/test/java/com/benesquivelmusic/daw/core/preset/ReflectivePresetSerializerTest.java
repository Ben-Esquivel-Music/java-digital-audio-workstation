package com.benesquivelmusic.daw.core.preset;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.DelayProcessor;
import com.benesquivelmusic.daw.core.dsp.ReverbProcessor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link ReflectivePresetSerializer} covering the requirements
 * enumerated in the preset-serializer issue: snapshot completeness, full
 * restore, round-trip preservation, forward-compatible unknown-key handling,
 * and clamping of out-of-range values.
 */
class ReflectivePresetSerializerTest {

    private static final double EPS = 1e-9;

    @Test
    void isSupportedReturnsTrueForAnnotatedProcessor() {
        assertThat(ReflectivePresetSerializer.isSupported(newCompressor())).isTrue();
    }

    @Test
    void isSupportedReturnsFalseForNull() {
        assertThat(ReflectivePresetSerializer.isSupported(null)).isFalse();
    }

    @Test
    void snapshotCapturesAllAnnotatedParameters() {
        CompressorProcessor comp = newCompressor();
        comp.setThresholdDb(-30.0);
        comp.setRatio(8.0);
        comp.setAttackMs(5.0);
        comp.setReleaseMs(250.0);
        comp.setKneeDb(12.0);
        comp.setMakeupGainDb(6.0);

        Map<String, Double> snap = ReflectivePresetSerializer.snapshot(comp);

        assertThat(snap).containsOnlyKeys(
                "Threshold", "Ratio", "Attack", "Release", "Knee", "Makeup Gain");
        assertThat(snap.get("Threshold")).isCloseTo(-30.0, within(EPS));
        assertThat(snap.get("Ratio")).isCloseTo(8.0, within(EPS));
        assertThat(snap.get("Attack")).isCloseTo(5.0, within(EPS));
        assertThat(snap.get("Release")).isCloseTo(250.0, within(EPS));
        assertThat(snap.get("Knee")).isCloseTo(12.0, within(EPS));
        assertThat(snap.get("Makeup Gain")).isCloseTo(6.0, within(EPS));
    }

    @Test
    void restoreAppliesAllValues() {
        CompressorProcessor comp = newCompressor();
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("Threshold", -24.0);
        values.put("Ratio", 5.0);
        values.put("Attack", 12.0);
        values.put("Release", 180.0);
        values.put("Knee", 8.0);
        values.put("Makeup Gain", 4.0);

        int applied = ReflectivePresetSerializer.restore(comp, values);

        assertThat(applied).isEqualTo(6);
        assertThat(comp.getThresholdDb()).isCloseTo(-24.0, within(EPS));
        assertThat(comp.getRatio()).isCloseTo(5.0, within(EPS));
        assertThat(comp.getAttackMs()).isCloseTo(12.0, within(EPS));
        assertThat(comp.getReleaseMs()).isCloseTo(180.0, within(EPS));
        assertThat(comp.getKneeDb()).isCloseTo(8.0, within(EPS));
        assertThat(comp.getMakeupGainDb()).isCloseTo(4.0, within(EPS));
    }

    @Test
    void roundTripSnapshotSerializeDeserializeRestorePreservesAllValues() {
        CompressorProcessor original = newCompressor();
        original.setThresholdDb(-27.5);
        original.setRatio(6.5);
        original.setAttackMs(7.25);
        original.setReleaseMs(150.0);
        original.setKneeDb(4.0);
        original.setMakeupGainDb(2.5);

        // Snapshot → JSON → parse → restore onto a fresh instance
        String json = ReflectivePresetSerializer.toJson(
                ReflectivePresetSerializer.snapshot(original));
        Map<String, Double> parsed = ReflectivePresetSerializer.fromJson(json);

        CompressorProcessor restored = newCompressor();
        ReflectivePresetSerializer.restore(restored, parsed);

        assertThat(restored.getThresholdDb()).isCloseTo(original.getThresholdDb(), within(EPS));
        assertThat(restored.getRatio()).isCloseTo(original.getRatio(), within(EPS));
        assertThat(restored.getAttackMs()).isCloseTo(original.getAttackMs(), within(EPS));
        assertThat(restored.getReleaseMs()).isCloseTo(original.getReleaseMs(), within(EPS));
        assertThat(restored.getKneeDb()).isCloseTo(original.getKneeDb(), within(EPS));
        assertThat(restored.getMakeupGainDb()).isCloseTo(original.getMakeupGainDb(), within(EPS));
    }

    @Test
    void unknownKeysAreSkippedGracefully() {
        CompressorProcessor comp = newCompressor();
        comp.setThresholdDb(-15.0);
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("Threshold", -20.0);
        values.put("LookAhead", 5.0); // future parameter — must be ignored
        values.put("UnknownKnob", 99.0);

        int applied = ReflectivePresetSerializer.restore(comp, values);

        assertThat(applied).isEqualTo(1);
        assertThat(comp.getThresholdDb()).isCloseTo(-20.0, within(EPS));
    }

    @Test
    void outOfRangeValuesAreClampedToDeclaredMinMax() {
        CompressorProcessor comp = newCompressor();
        Map<String, Double> values = new LinkedHashMap<>();
        // Threshold declared range: [-60, 0]
        values.put("Threshold", -1000.0);
        // Ratio declared range: [1, 20]
        values.put("Ratio", 999.0);
        // Attack declared range: [0.01, 100]
        values.put("Attack", -50.0);

        ReflectivePresetSerializer.restore(comp, values);

        assertThat(comp.getThresholdDb()).isCloseTo(-60.0, within(EPS));
        assertThat(comp.getRatio()).isCloseTo(20.0, within(EPS));
        assertThat(comp.getAttackMs()).isCloseTo(0.01, within(EPS));
    }

    @Test
    void nanAndNullValuesAreIgnored() {
        CompressorProcessor comp = newCompressor();
        comp.setThresholdDb(-10.0);
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("Threshold", Double.NaN);
        values.put("Ratio", null);

        int applied = ReflectivePresetSerializer.restore(comp, values);

        assertThat(applied).isZero();
        assertThat(comp.getThresholdDb()).isCloseTo(-10.0, within(EPS));
    }

    @Test
    void jsonRoundTripPreservesValues() {
        Map<String, Double> snap = new LinkedHashMap<>();
        snap.put("Threshold", -18.5);
        snap.put("Ratio", 3.25);
        snap.put("Mix", 0.0);

        String json = ReflectivePresetSerializer.toJson(snap);
        Map<String, Double> parsed = ReflectivePresetSerializer.fromJson(json);

        assertThat(parsed).containsEntry("Threshold", -18.5);
        assertThat(parsed).containsEntry("Ratio", 3.25);
        assertThat(parsed).containsEntry("Mix", 0.0);
    }

    @Test
    void jsonEscapesSpecialCharactersInKeys() {
        Map<String, Double> snap = new LinkedHashMap<>();
        snap.put("Q\"uote", 1.0);
        snap.put("Back\\slash", 2.0);

        String json = ReflectivePresetSerializer.toJson(snap);
        Map<String, Double> parsed = ReflectivePresetSerializer.fromJson(json);

        assertThat(parsed).containsEntry("Q\"uote", 1.0);
        assertThat(parsed).containsEntry("Back\\slash", 2.0);
    }

    @Test
    void toXmlProducesWellFormedOutput() {
        Map<String, Double> snap = new LinkedHashMap<>();
        snap.put("Threshold", -20.0);
        snap.put("Ratio", 4.0);

        String xml = ReflectivePresetSerializer.toXml(snap);

        assertThat(xml).contains("<parameters>").contains("</parameters>");
        assertThat(xml).contains("name=\"Threshold\"");
        assertThat(xml).contains("name=\"Ratio\"");
        assertThat(xml).contains("value=\"-20.0\"");
        assertThat(xml).contains("value=\"4.0\"");
    }

    @Test
    void snapshotWorksAcrossMultipleProcessorTypes() {
        ReverbProcessor rev = new ReverbProcessor(2, 44100.0);
        rev.setRoomSize(0.7);
        rev.setDecay(0.4);
        rev.setDamping(0.2);
        rev.setMix(0.3);

        Map<String, Double> snap = ReflectivePresetSerializer.snapshot(rev);
        assertThat(snap).containsOnlyKeys("Room Size", "Decay", "Damping", "Mix");

        ReverbProcessor restored = new ReverbProcessor(2, 44100.0);
        ReflectivePresetSerializer.restore(restored, snap);
        assertThat(restored.getRoomSize()).isCloseTo(0.7, within(EPS));
        assertThat(restored.getDecay()).isCloseTo(0.4, within(EPS));
        assertThat(restored.getDamping()).isCloseTo(0.2, within(EPS));
        assertThat(restored.getMix()).isCloseTo(0.3, within(EPS));
    }

    @Test
    void delayRoundTripWorks() {
        DelayProcessor d = new DelayProcessor(2, 44100.0);
        d.setDelayMs(125.0);
        d.setFeedback(0.55);
        d.setMix(0.4);

        String json = ReflectivePresetSerializer.toJson(
                ReflectivePresetSerializer.snapshot(d));
        DelayProcessor restored = new DelayProcessor(2, 44100.0);
        ReflectivePresetSerializer.restore(restored,
                ReflectivePresetSerializer.fromJson(json));

        assertThat(restored.getDelayMs()).isCloseTo(125.0, within(EPS));
        assertThat(restored.getFeedback()).isCloseTo(0.55, within(EPS));
        assertThat(restored.getMix()).isCloseTo(0.4, within(EPS));
    }

    private static CompressorProcessor newCompressor() {
        return new CompressorProcessor(2, 44100.0);
    }
}
