package com.benesquivelmusic.daw.sdk.audio.performance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DegradationPolicyTest {

    @Test
    void reduceOversamplingRejectsFactorBelowOne() {
        assertThatThrownBy(() -> new DegradationPolicy.ReduceOversampling(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reduceOversamplingAcceptsFallbackFactorOne() {
        assertThat(new DegradationPolicy.ReduceOversampling(1).fallbackFactor()).isEqualTo(1);
    }

    @Test
    void substituteSimpleKernelRejectsNullId() {
        assertThatThrownBy(() -> new DegradationPolicy.SubstituteSimpleKernel(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void substituteSimpleKernelRejectsBlankId() {
        assertThatThrownBy(() -> new DegradationPolicy.SubstituteSimpleKernel("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void patternMatchingExhaustiveOverSealedHierarchy() {
        // Compiles only if all permitted subtypes are handled — JEP 441.
        DegradationPolicy p = new DegradationPolicy.BypassExpensive();
        String tag = switch (p) {
            case DegradationPolicy.BypassExpensive be        -> "bypass";
            case DegradationPolicy.ReduceOversampling(int f) -> "oversample-" + f;
            case DegradationPolicy.SubstituteSimpleKernel(String id) -> "swap-" + id;
            case DegradationPolicy.DoNothing dn              -> "noop";
        };
        assertThat(tag).isEqualTo("bypass");
    }
}
