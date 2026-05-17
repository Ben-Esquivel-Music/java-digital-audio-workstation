package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEntryTest {

    /**
     * Test-local convenience for the action-less shape. Production always
     * builds the canonical five-argument record (story 273); only tests
     * want the short form, so the convenience lives here, not on the record.
     */
    private static NotificationEntry entry(Instant timestamp,
                                           NotificationLevel level,
                                           String message) {
        return new NotificationEntry(timestamp, level, message,
                Optional.empty(), Optional.empty());
    }

    @Test
    void shouldCreateEntryWithAllFields() {
        Instant now = Instant.now();
        NotificationEntry entry = entry(now, NotificationLevel.ERROR, "Test error");

        assertThat(entry.timestamp()).isEqualTo(now);
        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(entry.message()).isEqualTo("Test error");
    }

    @Test
    void shouldRejectNullTimestamp() {
        assertThatThrownBy(() -> entry(null, NotificationLevel.INFO, "msg"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    void shouldRejectNullLevel() {
        assertThatThrownBy(() -> entry(Instant.now(), null, "msg"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("level");
    }

    @Test
    void shouldRejectNullMessage() {
        assertThatThrownBy(() -> entry(Instant.now(), NotificationLevel.INFO, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("message");
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        Instant now = Instant.now();
        NotificationEntry a = entry(now, NotificationLevel.WARNING, "warn");
        NotificationEntry b = entry(now, NotificationLevel.WARNING, "warn");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldNotBeEqualWithDifferentLevel() {
        Instant now = Instant.now();
        NotificationEntry a = entry(now, NotificationLevel.WARNING, "msg");
        NotificationEntry b = entry(now, NotificationLevel.ERROR, "msg");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void shouldNotBeEqualWithDifferentMessage() {
        Instant now = Instant.now();
        NotificationEntry a = entry(now, NotificationLevel.ERROR, "msg1");
        NotificationEntry b = entry(now, NotificationLevel.ERROR, "msg2");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toStringShouldContainAllFields() {
        Instant now = Instant.now();
        NotificationEntry entry = entry(now, NotificationLevel.SUCCESS, "hello");

        String str = entry.toString();
        assertThat(str).contains("SUCCESS");
        assertThat(str).contains("hello");
    }
}
