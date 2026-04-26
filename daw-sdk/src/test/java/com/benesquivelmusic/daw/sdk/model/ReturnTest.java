package com.benesquivelmusic.daw.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReturnTest {

    @Test
    void of_createsUnityCentredUnmuted() {
        Return r = Return.of("Reverb");
        assertThat(r.name()).isEqualTo("Reverb");
        assertThat(r.volume()).isEqualTo(1.0);
        assertThat(r.pan()).isEqualTo(0.0);
        assertThat(r.muted()).isFalse();
    }

    @Test
    void rangeValidation() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new Return(id, "x", -0.1, 0.0, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Return(id, "x", 0.5, 1.5, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withers_areImmutable() {
        Return a = Return.of("R");
        Return b = a.withMuted(true).withVolume(0.5);

        assertThat(a.muted()).isFalse();
        assertThat(a.volume()).isEqualTo(1.0);
        assertThat(b.muted()).isTrue();
        assertThat(b.volume()).isEqualTo(0.5);
        assertThat(a.id()).isEqualTo(b.id());
    }
}
