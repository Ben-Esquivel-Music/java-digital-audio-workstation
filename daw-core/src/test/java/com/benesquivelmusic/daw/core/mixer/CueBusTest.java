package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CueBusTest {

    @Test
    void shouldCreateEmptyBus() {
        CueBus bus = CueBus.create("Singer", 2);
        assertThat(bus.label()).isEqualTo("Singer");
        assertThat(bus.hardwareOutputIndex()).isEqualTo(2);
        assertThat(bus.masterGain()).isEqualTo(1.0);
        assertThat(bus.sends()).isEmpty();
    }

    @Test
    void withSendReplacesExistingSendForSameTrack() {
        CueBus bus = CueBus.create("Singer", 1);
        UUID t = UUID.randomUUID();
        CueBus b1 = bus.withSend(new CueSend(t, 0.5, 0.0, true));
        CueBus b2 = b1.withSend(new CueSend(t, 0.9, 0.5, false));

        assertThat(b2.sends()).hasSize(1);
        assertThat(b2.findSend(t).gain()).isEqualTo(0.9);
        assertThat(b2.findSend(t).preFader()).isFalse();
    }

    @Test
    void withoutSendRemovesSendForTrack() {
        CueBus bus = CueBus.create("Singer", 1)
                .withSend(new CueSend(UUID.randomUUID(), 0.5, 0.0, true));
        UUID t = UUID.randomUUID();
        bus = bus.withSend(new CueSend(t, 0.5, 0.0, true));
        assertThat(bus.sends()).hasSize(2);

        CueBus stripped = bus.withoutSend(t);
        assertThat(stripped.sends()).hasSize(1);
        assertThat(stripped.findSend(t)).isNull();
    }

    @Test
    void sendsListIsImmutable() {
        CueBus bus = CueBus.create("Singer", 1);
        assertThatThrownBy(() -> bus.sends().add(new CueSend(UUID.randomUUID(), 0.1, 0.0, true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertThatThrownBy(() -> new CueBus(UUID.randomUUID(), "x", -1, java.util.List.of(), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CueBus(UUID.randomUUID(), "x", 0, java.util.List.of(), 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CueSend(UUID.randomUUID(), 1.5, 0.0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CueSend(UUID.randomUUID(), 0.5, 1.5, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
