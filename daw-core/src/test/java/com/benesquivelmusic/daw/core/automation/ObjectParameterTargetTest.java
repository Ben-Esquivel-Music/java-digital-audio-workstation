package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.sdk.spatial.ObjectParameter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectParameterTargetTest {

    @Test
    void shouldExposeRangeAndDefaultFromParameter() {
        ObjectParameterTarget target = new ObjectParameterTarget("obj-1", ObjectParameter.X);

        assertThat(target.getMinValue()).isEqualTo(-1.0);
        assertThat(target.getMaxValue()).isEqualTo(1.0);
        assertThat(target.getDefaultValue()).isEqualTo(0.0);
        assertThat(target.displayName()).isEqualTo("X");
        assertThat(target.isValidValue(0.5)).isTrue();
        assertThat(target.isValidValue(2.0)).isFalse();
    }

    @Test
    void gainTargetShouldDefaultToUnity() {
        ObjectParameterTarget target = new ObjectParameterTarget("obj-1", ObjectParameter.GAIN);

        assertThat(target.getDefaultValue()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectNullOrBlankInstanceId() {
        assertThatThrownBy(() -> new ObjectParameterTarget(null, ObjectParameter.X))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ObjectParameterTarget("", ObjectParameter.X))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObjectParameterTarget("   ", ObjectParameter.X))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullParameter() {
        assertThatThrownBy(() -> new ObjectParameterTarget("obj-1", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityShouldCoverInstanceIdAndParameter() {
        ObjectParameterTarget a = new ObjectParameterTarget("obj-1", ObjectParameter.X);
        ObjectParameterTarget b = new ObjectParameterTarget("obj-1", ObjectParameter.X);
        ObjectParameterTarget differentParam =
                new ObjectParameterTarget("obj-1", ObjectParameter.Y);
        ObjectParameterTarget differentInstance =
                new ObjectParameterTarget("obj-2", ObjectParameter.X);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(differentParam);
        assertThat(a).isNotEqualTo(differentInstance);
    }

    @Test
    void shouldBeUsableAsAutomationTarget() {
        AutomationTarget target = new ObjectParameterTarget("obj-1", ObjectParameter.SIZE);

        assertThat(target).isInstanceOf(ObjectParameterTarget.class);
        assertThat(target.getMaxValue()).isEqualTo(1.0);
    }
}
