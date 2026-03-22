package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WallMaterialTest {

    @Test
    void shouldHaveAbsorptionCoefficientInValidRange() {
        for (WallMaterial material : WallMaterial.values()) {
            assertThat(material.absorptionCoefficient())
                    .as("absorption coefficient for %s", material.name())
                    .isBetween(0.0, 1.0);
        }
    }

    @Test
    void concreteShouldBeMostReflective() {
        assertThat(WallMaterial.CONCRETE.absorptionCoefficient())
                .isLessThan(WallMaterial.ACOUSTIC_FOAM.absorptionCoefficient());
    }

    @Test
    void acousticFoamShouldBeHighlyAbsorptive() {
        assertThat(WallMaterial.ACOUSTIC_FOAM.absorptionCoefficient()).isGreaterThanOrEqualTo(0.5);
    }
}
