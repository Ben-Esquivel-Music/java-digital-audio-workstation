package com.benesquivelmusic.daw.sdk.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginMeterSnapshotTest {

    @Test
    void silentShouldReportZeroGainReductionAndNegInfLevels() {
        var snap = PluginMeterSnapshot.SILENT;
        assertThat(snap.gainReductionDb()).isZero();
        assertThat(snap.inputLevelDb()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(snap.outputLevelDb()).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void ofGainReductionShouldPopulateGrAndLeaveLevelsUndefined() {
        var snap = PluginMeterSnapshot.ofGainReduction(-3.5);
        assertThat(snap.gainReductionDb()).isEqualTo(-3.5);
        assertThat(snap.inputLevelDb()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(snap.outputLevelDb()).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void recordShouldBeValueEqual() {
        assertThat(new PluginMeterSnapshot(-2.0, -10.0, -12.0))
                .isEqualTo(new PluginMeterSnapshot(-2.0, -10.0, -12.0));
    }
}
