package com.benesquivelmusic.daw.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SendTest {

    @Test
    void of_createsZeroLevelPostFaderSend() {
        UUID target = UUID.randomUUID();
        Send s = Send.of(target);

        assertThat(s.targetId()).isEqualTo(target);
        assertThat(s.level()).isEqualTo(0.0);
        assertThat(s.tap()).isEqualTo(SendTap.POST_FADER);
    }

    @Test
    void withLevel_validatesRange() {
        Send s = Send.of(UUID.randomUUID());
        assertThat(s.withLevel(0.5).level()).isEqualTo(0.5);
        assertThatThrownBy(() -> s.withLevel(-0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.withLevel(1.01))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withTap_doesNotMutateOriginal() {
        Send a = Send.of(UUID.randomUUID());
        Send b = a.withTap(SendTap.PRE_INSERTS);
        assertThat(a.tap()).isEqualTo(SendTap.POST_FADER);
        assertThat(b.tap()).isEqualTo(SendTap.PRE_INSERTS);
    }
}
