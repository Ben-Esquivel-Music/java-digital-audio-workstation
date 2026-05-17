package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationLevelTest {

    @Test
    void shouldHaveFourLevels() {
        assertThat(NotificationLevel.values()).hasSize(4);
    }

    @Test
    void successShouldHaveGreenStyle() {
        NotificationLevel level = NotificationLevel.SUCCESS;
        assertThat(level.styleClass()).isEqualTo("notification-success");
        assertThat(level.icon()).isEqualTo(DawIcon.SUCCESS);
    }

    @Test
    void infoShouldHaveBlueStyle() {
        NotificationLevel level = NotificationLevel.INFO;
        assertThat(level.styleClass()).isEqualTo("notification-info");
        assertThat(level.icon()).isEqualTo(DawIcon.INFO_CIRCLE);
    }

    @Test
    void warningShouldHaveOrangeStyle() {
        NotificationLevel level = NotificationLevel.WARNING;
        assertThat(level.styleClass()).isEqualTo("notification-warning");
        assertThat(level.icon()).isEqualTo(DawIcon.WARNING);
    }

    @Test
    void errorShouldHaveRedStyle() {
        NotificationLevel level = NotificationLevel.ERROR;
        assertThat(level.styleClass()).isEqualTo("notification-error");
        assertThat(level.icon()).isEqualTo(DawIcon.ERROR);
    }

    @ParameterizedTest
    @EnumSource(NotificationLevel.class)
    void allLevelsShouldHaveNonBlankStyleClass(NotificationLevel level) {
        assertThat(level.styleClass()).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(NotificationLevel.class)
    void allLevelsShouldHaveNonNullIcon(NotificationLevel level) {
        assertThat(level.icon()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(NotificationLevel.class)
    void styleClassesShouldFollowNamingConvention(NotificationLevel level) {
        assertThat(level.styleClass()).startsWith("notification-");
    }

    @Test
    void shouldResolveByName() {
        assertThat(NotificationLevel.valueOf("SUCCESS")).isEqualTo(NotificationLevel.SUCCESS);
        assertThat(NotificationLevel.valueOf("INFO")).isEqualTo(NotificationLevel.INFO);
        assertThat(NotificationLevel.valueOf("WARNING")).isEqualTo(NotificationLevel.WARNING);
        assertThat(NotificationLevel.valueOf("ERROR")).isEqualTo(NotificationLevel.ERROR);
    }
}
