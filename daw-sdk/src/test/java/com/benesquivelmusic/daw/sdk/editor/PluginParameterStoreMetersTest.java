package com.benesquivelmusic.daw.sdk.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the {@link PluginParameterStore} meter-snapshot channel (Plugin View
 * Design Book §6.4, story 301): the audio side publishes an immutable
 * {@link PluginMeterSnapshot} reference, the FX side reads the latest one.
 */
class PluginParameterStoreMetersTest {

    private static PluginParameterStore newStore() {
        return new PluginParameterStore(List.of(
                new PluginParameter(1, "Gain", -60.0, 12.0, 0.0)));
    }

    @Test
    void metersShouldBeSilentBeforeFirstPublish() {
        assertThat(newStore().meters()).isSameAs(PluginMeterSnapshot.SILENT);
    }

    @Test
    void publishMetersShouldRoundTripTheSameInstance() {
        var store = newStore();
        var snapshot = new PluginMeterSnapshot(-2.5, -10.0, -12.5);

        store.publishMeters(snapshot);

        assertThat(store.meters()).isSameAs(snapshot);
    }

    @Test
    void publishMetersShouldRejectNull() {
        assertThatThrownBy(() -> newStore().publishMeters(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void publishMetersShouldBeLatestWinsAcrossTwoPublishes() {
        var store = newStore();
        var first = PluginMeterSnapshot.ofGainReduction(-1.0);
        var second = PluginMeterSnapshot.ofGainReduction(-6.0);

        store.publishMeters(first);
        store.publishMeters(second);

        assertThat(store.meters()).isSameAs(second);
    }
}
