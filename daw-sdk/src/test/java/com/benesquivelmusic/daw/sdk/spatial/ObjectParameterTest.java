package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectParameterTest {

    @Test
    void positionParametersShouldHaveBipolarRange() {
        for (ObjectParameter p : new ObjectParameter[]{
                ObjectParameter.X, ObjectParameter.Y, ObjectParameter.Z}) {
            assertThat(p.getMinValue()).isEqualTo(-1.0);
            assertThat(p.getMaxValue()).isEqualTo(1.0);
            assertThat(p.getDefaultValue()).isEqualTo(0.0);
        }
    }

    @Test
    void sizeAndDivergenceShouldBeUnitInterval() {
        assertThat(ObjectParameter.SIZE.getMinValue()).isEqualTo(0.0);
        assertThat(ObjectParameter.SIZE.getMaxValue()).isEqualTo(1.0);
        assertThat(ObjectParameter.SIZE.getDefaultValue()).isEqualTo(0.0);

        assertThat(ObjectParameter.DIVERGENCE.getMinValue()).isEqualTo(0.0);
        assertThat(ObjectParameter.DIVERGENCE.getMaxValue()).isEqualTo(1.0);
        assertThat(ObjectParameter.DIVERGENCE.getDefaultValue()).isEqualTo(0.0);
    }

    @Test
    void gainShouldDefaultToUnity() {
        assertThat(ObjectParameter.GAIN.getMinValue()).isEqualTo(0.0);
        assertThat(ObjectParameter.GAIN.getMaxValue()).isEqualTo(1.0);
        assertThat(ObjectParameter.GAIN.getDefaultValue()).isEqualTo(1.0);
    }

    @Test
    void displayNameShouldBeEnumName() {
        assertThat(ObjectParameter.X.displayName()).isEqualTo("X");
        assertThat(ObjectParameter.SIZE.displayName()).isEqualTo("SIZE");
    }

    @Test
    void isValidValueShouldReflectRange() {
        assertThat(ObjectParameter.X.isValidValue(0.0)).isTrue();
        assertThat(ObjectParameter.X.isValidValue(-1.0)).isTrue();
        assertThat(ObjectParameter.X.isValidValue(1.0)).isTrue();
        assertThat(ObjectParameter.X.isValidValue(-1.0001)).isFalse();
        assertThat(ObjectParameter.X.isValidValue(1.0001)).isFalse();

        assertThat(ObjectParameter.SIZE.isValidValue(-0.001)).isFalse();
        assertThat(ObjectParameter.SIZE.isValidValue(0.5)).isTrue();
    }

    @Test
    void rangesShouldMatchObjectMetadataFields() {
        // X / Y / Z / SIZE / GAIN must agree with ObjectMetadata so an
        // automation lane can write directly into per-frame metadata.
        ObjectMetadata m = new ObjectMetadata(
                ObjectParameter.X.getMaxValue(),
                ObjectParameter.Y.getMinValue(),
                ObjectParameter.Z.getMaxValue(),
                ObjectParameter.SIZE.getMaxValue(),
                ObjectParameter.GAIN.getMaxValue());
        assertThat(m.x()).isEqualTo(1.0);
        assertThat(m.y()).isEqualTo(-1.0);
    }

    @Test
    void enumShouldExposeAllSixParameters() {
        assertThat(ObjectParameter.values()).containsExactly(
                ObjectParameter.X,
                ObjectParameter.Y,
                ObjectParameter.Z,
                ObjectParameter.SIZE,
                ObjectParameter.DIVERGENCE,
                ObjectParameter.GAIN);
    }
}
