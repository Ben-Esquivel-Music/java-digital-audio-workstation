package com.benesquivelmusic.daw.core.mixer.spatial;

import com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BedBusTest {

    @Test
    void unityGainBusHasZeroDbOnEveryChannel() {
        BedBus bus = BedBus.unityGain(UUID.randomUUID(), ImmersiveFormat.FORMAT_7_1_4);
        assertThat(bus.channelCount()).isEqualTo(12);
        for (double g : bus.channelGainsDb()) {
            assertThat(g).isEqualTo(0.0);
        }
    }

    @Test
    void rejectsGainArrayOfWrongLength() {
        assertThatThrownBy(() ->
                new BedBus(UUID.randomUUID(), ImmersiveFormat.FORMAT_5_1, new double[3]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordIsImmutableAfterMutationOfReturnedArray() {
        BedBus bus = BedBus.unityGain(UUID.randomUUID(), ImmersiveFormat.FORMAT_5_1);
        double[] gains = bus.channelGainsDb();
        gains[0] = 999.0;
        // Defensive copy: the bus remains unchanged.
        assertThat(bus.channelGainsDb()[0]).isEqualTo(0.0);
    }

    @Test
    void format916HasSixteenChannels() {
        BedBus bus = BedBus.unityGain(UUID.randomUUID(), ImmersiveFormat.FORMAT_9_1_6);
        assertThat(bus.channelCount()).isEqualTo(16);
        assertThat(bus.format().layout().indexOf(SpeakerLabel.LTS)).isGreaterThanOrEqualTo(0);
        assertThat(bus.format().layout().indexOf(SpeakerLabel.LW)).isGreaterThanOrEqualTo(0);
    }
}
