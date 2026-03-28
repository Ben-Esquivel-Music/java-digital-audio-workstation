package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEntryTest {

    @Test
    void shouldCreateEntryWithAllFields() {
        Instant now = Instant.now();
        NotificationEntry entry = new NotificationEntry(now, NotificationLevel.ERROR, "Test error");

        assertThat(entry.timestamp()).isEqualTo(now);
        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(entry.message()).isEqualTo("Test error");
    }

    @Test
    void shouldRejectNullTimestamp() {
        assertThatThrownBy(() -> new NotificationEntry(null, NotificationLevel.INFO, "msg"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    void shouldRejectNullLevel() {
        assertThatThrownBy(() -> new NotificationEntry(Instant.now(), null, "msg"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("level");
    }

    @Test
    void shouldRejectNullMessage() {
        assertThatThrownBy(() -> new NotificationEntry(Instant.now(), NotificationLevel.INFO, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("message");
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        Instant now = Instant.now();
        NotificationEntry a = new NotificationEntry(now, NotificationLevel.WARNING, "warn");
        NotificationEntry b = new NotificationEntry(now, NotificationLevel.WARNING, "warn");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldNotBeEqualWithDifferentLevel() {
        Instant now = Instant.now();
        NotificationEntry a = new NotificationEntry(now, NotificationLevel.WARNING, "msg");
        NotificationEntry b = new NotificationEntry(now, NotificationLevel.ERROR, "msg");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void shouldNotBeEqualWithDifferentMessage() {
        Instant now = Instant.now();
        NotificationEntry a = new NotificationEntry(now, NotificationLevel.ERROR, "msg1");
        NotificationEntry b = new NotificationEntry(now, NotificationLevel.ERROR, "msg2");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toStringShouldContainAllFields() {
        Instant now = Instant.now();
        NotificationEntry entry = new NotificationEntry(now, NotificationLevel.SUCCESS, "hello");

        String str = entry.toString();
        assertThat(str).contains("SUCCESS");
        assertThat(str).contains("hello");
    }
}
