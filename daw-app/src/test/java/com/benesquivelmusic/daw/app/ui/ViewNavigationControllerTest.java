package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link ViewNavigationController} helper logic that can be
 * exercised without a live JavaFX scene or toolkit.
 */
class ViewNavigationControllerTest {

    @Test
    void shouldCreateViewNavigationControllerClass() {
        Class<?> hostClass = ViewNavigationController.Host.class;
        assertThat(hostClass).isNotNull();
        assertThat(hostClass.isInterface()).isTrue();
    }

    @Test
    void hostInterfaceShouldDeclareExpectedMethods() throws NoSuchMethodException {
        Class<?> hostClass = ViewNavigationController.Host.class;
        assertThat(hostClass.getMethod("project")).isNotNull();
        assertThat(hostClass.getMethod("onEditorTrim")).isNotNull();
        assertThat(hostClass.getMethod("onEditorFadeIn")).isNotNull();
        assertThat(hostClass.getMethod("onEditorFadeOut")).isNotNull();
    }

    @Test
    void classShouldBeFinalAndPackagePrivate() {
        Class<?> clazz = ViewNavigationController.class;
        assertThat(java.lang.reflect.Modifier.isFinal(clazz.getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isPublic(clazz.getModifiers())).isFalse();
    }

}
