package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectMetadataTest {

    @Test
    void shouldCreateDefaultMetadata() {
        ObjectMetadata meta = ObjectMetadata.DEFAULT;
        assertThat(meta.x()).isEqualTo(0.0);
        assertThat(meta.y()).isEqualTo(0.0);
        assertThat(meta.z()).isEqualTo(0.0);
        assertThat(meta.size()).isEqualTo(0.0);
        assertThat(meta.gain()).isEqualTo(1.0);
    }

    @Test
    void shouldCreateWithValidValues() {
        ObjectMetadata meta = new ObjectMetadata(0.5, -0.3, 0.8, 0.5, 0.7);
        assertThat(meta.x()).isEqualTo(0.5);
        assertThat(meta.y()).isEqualTo(-0.3);
        assertThat(meta.z()).isEqualTo(0.8);
        assertThat(meta.size()).isEqualTo(0.5);
        assertThat(meta.gain()).isEqualTo(0.7);
    }

    @Test
    void shouldRejectXOutOfRange() {
        assertThatThrownBy(() -> new ObjectMetadata(1.1, 0, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x");
        assertThatThrownBy(() -> new ObjectMetadata(-1.1, 0, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x");
    }

    @Test
    void shouldRejectYOutOfRange() {
        assertThatThrownBy(() -> new ObjectMetadata(0, 1.1, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("y");
    }

    @Test
    void shouldRejectZOutOfRange() {
        assertThatThrownBy(() -> new ObjectMetadata(0, 0, -1.1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("z");
    }

    @Test
    void shouldRejectSizeOutOfRange() {
        assertThatThrownBy(() -> new ObjectMetadata(0, 0, 0, -0.1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> new ObjectMetadata(0, 0, 0, 1.1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
    }

    @Test
    void shouldRejectGainOutOfRange() {
        assertThatThrownBy(() -> new ObjectMetadata(0, 0, 0, 0, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gain");
        assertThatThrownBy(() -> new ObjectMetadata(0, 0, 0, 0, 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gain");
    }

    @Test
    void shouldUpdatePosition() {
        ObjectMetadata meta = ObjectMetadata.DEFAULT.withPosition(0.5, -0.5, 0.3);
        assertThat(meta.x()).isEqualTo(0.5);
        assertThat(meta.y()).isEqualTo(-0.5);
        assertThat(meta.z()).isEqualTo(0.3);
        assertThat(meta.size()).isEqualTo(0.0);
        assertThat(meta.gain()).isEqualTo(1.0);
    }

    @Test
    void shouldUpdateGain() {
        ObjectMetadata meta = ObjectMetadata.DEFAULT.withGain(0.5);
        assertThat(meta.gain()).isEqualTo(0.5);
        assertThat(meta.x()).isEqualTo(0.0);
    }

    @Test
    void shouldAllowBoundaryValues() {
        ObjectMetadata meta = new ObjectMetadata(-1.0, -1.0, -1.0, 0.0, 0.0);
        assertThat(meta.x()).isEqualTo(-1.0);

        ObjectMetadata meta2 = new ObjectMetadata(1.0, 1.0, 1.0, 1.0, 1.0);
        assertThat(meta2.x()).isEqualTo(1.0);
    }
}
