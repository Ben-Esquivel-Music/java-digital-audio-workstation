package com.benesquivelmusic.daw.sdk.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class RealTimeSafeTest {

    @Test
    void shouldBeRetainedAtRuntime() {
        var retention = RealTimeSafe.class.getAnnotation(Retention.class);
        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void shouldTargetMethodAndType() {
        var target = RealTimeSafe.class.getAnnotation(Target.class);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactlyInAnyOrder(ElementType.METHOD, ElementType.TYPE);
    }

    @Test
    void shouldBeDocumented() {
        assertThat(RealTimeSafe.class.getAnnotation(Documented.class)).isNotNull();
    }

    @RealTimeSafe
    static class AnnotatedClass {}

    @Test
    void shouldBeDetectableOnType() {
        assertThat(AnnotatedClass.class.isAnnotationPresent(RealTimeSafe.class)).isTrue();
    }

    static class MethodHolder {
        @RealTimeSafe
        void process() {}
    }

    @Test
    void shouldBeDetectableOnMethod() throws NoSuchMethodException {
        var method = MethodHolder.class.getDeclaredMethod("process");
        assertThat(method.isAnnotationPresent(RealTimeSafe.class)).isTrue();
    }
}
