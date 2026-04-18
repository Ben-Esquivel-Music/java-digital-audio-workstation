package com.benesquivelmusic.daw.sdk.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.*;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessorParamTest {

    @Test
    void shouldBeRetainedAtRuntime() {
        Retention retention = ProcessorParam.class.getAnnotation(Retention.class);
        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void shouldTargetMethod() {
        Target target = ProcessorParam.class.getAnnotation(Target.class);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(ElementType.METHOD);
    }

    @Test
    void shouldBeDocumented() {
        assertThat(ProcessorParam.class.getAnnotation(Documented.class)).isNotNull();
    }

    @Test
    void shouldExposeDeclaredAttributes() throws NoSuchMethodException {
        Method m = Sample.class.getMethod("getThresholdDb");
        ProcessorParam ann = m.getAnnotation(ProcessorParam.class);
        assertThat(ann).isNotNull();
        assertThat(ann.id()).isEqualTo(0);
        assertThat(ann.name()).isEqualTo("Threshold");
        assertThat(ann.min()).isEqualTo(-60.0);
        assertThat(ann.max()).isEqualTo(0.0);
        assertThat(ann.defaultValue()).isEqualTo(-20.0);
        assertThat(ann.unit()).isEqualTo("dB");
    }

    @Test
    void unitShouldDefaultToEmptyString() throws NoSuchMethodException {
        Method m = Sample.class.getMethod("getRatio");
        ProcessorParam ann = m.getAnnotation(ProcessorParam.class);
        assertThat(ann).isNotNull();
        assertThat(ann.unit()).isEmpty();
    }

    static final class Sample {
        @ProcessorParam(id = 0, name = "Threshold", min = -60.0, max = 0.0,
                        defaultValue = -20.0, unit = "dB")
        public double getThresholdDb() { return 0.0; }

        @ProcessorParam(id = 1, name = "Ratio", min = 1.0, max = 20.0, defaultValue = 4.0)
        public double getRatio() { return 0.0; }
    }
}
