package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationHistoryServiceTest {

    private NotificationHistoryService service;

    @BeforeEach
    void setUp() {
        service = new NotificationHistoryService();
    }

    @Test
    void shouldStartEmpty() {
        assertThat(service.size()).isZero();
        assertThat(service.getEntries()).isEmpty();
    }

    @Test
    void shouldRecordWarningEntries() {
        service.record(NotificationLevel.WARNING, "Low disk space");

        assertThat(service.size()).isEqualTo(1);
        NotificationEntry entry = service.getEntries().getFirst();
        assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(entry.message()).isEqualTo("Low disk space");
        assertThat(entry.timestamp()).isNotNull();
    }

    @Test
    void shouldRecordErrorEntries() {
        service.record(NotificationLevel.ERROR, "Save failed");

        assertThat(service.size()).isEqualTo(1);
        NotificationEntry entry = service.getEntries().getFirst();
        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(entry.message()).isEqualTo("Save failed");
    }

    @Test
    void shouldIgnoreInfoEntries() {
        service.record(NotificationLevel.INFO, "Informational");

        assertThat(service.size()).isZero();
    }

    @Test
    void shouldIgnoreSuccessEntries() {
        service.record(NotificationLevel.SUCCESS, "Saved ok");

        assertThat(service.size()).isZero();
    }

    @Test
    void shouldRetainOrderOldestFirst() {
        service.record(NotificationLevel.ERROR, "first");
        service.record(NotificationLevel.WARNING, "second");
        service.record(NotificationLevel.ERROR, "third");

        List<NotificationEntry> entries = service.getEntries();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).message()).isEqualTo("first");
        assertThat(entries.get(1).message()).isEqualTo("second");
        assertThat(entries.get(2).message()).isEqualTo("third");
    }

    @Test
    void shouldEvictOldestWhenMaxExceeded() {
        NotificationHistoryService small = new NotificationHistoryService(3);
        small.record(NotificationLevel.ERROR, "a");
        small.record(NotificationLevel.ERROR, "b");
        small.record(NotificationLevel.ERROR, "c");
        small.record(NotificationLevel.ERROR, "d");

        assertThat(small.size()).isEqualTo(3);
        List<NotificationEntry> entries = small.getEntries();
        assertThat(entries.get(0).message()).isEqualTo("b");
        assertThat(entries.get(1).message()).isEqualTo("c");
        assertThat(entries.get(2).message()).isEqualTo("d");
    }

    @Test
    void clearShouldRemoveAllEntries() {
        service.record(NotificationLevel.ERROR, "error1");
        service.record(NotificationLevel.WARNING, "warn1");
        service.clear();

        assertThat(service.size()).isZero();
        assertThat(service.getEntries()).isEmpty();
    }

    @Test
    void shouldNotifyListenerOnRecord() {
        List<NotificationEntry> received = new ArrayList<>();
        service.addListener(received::add);

        service.record(NotificationLevel.ERROR, "new error");

        assertThat(received).hasSize(1);
        assertThat(received.getFirst().message()).isEqualTo("new error");
    }

    @Test
    void shouldNotifyListenerWithNullOnClear() {
        List<NotificationEntry> received = new ArrayList<>();
        service.addListener(received::add);

        service.record(NotificationLevel.ERROR, "error");
        service.clear();

        assertThat(received).hasSize(2);
        assertThat(received.get(0).message()).isEqualTo("error");
        assertThat(received.get(1)).isNull();
    }

    @Test
    void shouldNotNotifyListenerForIgnoredLevels() {
        AtomicInteger callCount = new AtomicInteger();
        service.addListener(_ -> callCount.incrementAndGet());

        service.record(NotificationLevel.INFO, "ignored");
        service.record(NotificationLevel.SUCCESS, "also ignored");

        assertThat(callCount.get()).isZero();
    }

    @Test
    void removeListenerShouldStopNotifications() {
        AtomicInteger callCount = new AtomicInteger();
        var listener = new java.util.function.Consumer<NotificationEntry>() {
            @Override
            public void accept(NotificationEntry e) {
                callCount.incrementAndGet();
            }
        };
        service.addListener(listener);
        service.record(NotificationLevel.ERROR, "first");
        assertThat(callCount.get()).isEqualTo(1);

        service.removeListener(listener);
        service.record(NotificationLevel.ERROR, "second");
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void shouldRejectNonPositiveMaxEntries() {
        assertThatThrownBy(() -> new NotificationHistoryService(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationHistoryService(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullLevel() {
        assertThatThrownBy(() -> service.record(null, "msg"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullMessage() {
        assertThatThrownBy(() -> service.record(NotificationLevel.ERROR, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullListener() {
        assertThatThrownBy(() -> service.addListener(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getEntriesShouldReturnUnmodifiableList() {
        service.record(NotificationLevel.ERROR, "test");
        List<NotificationEntry> entries = service.getEntries();

        assertThatThrownBy(() -> entries.add(
                new NotificationEntry(java.time.Instant.now(), NotificationLevel.ERROR, "hack")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaultMaxEntriesShouldBe200() {
        assertThat(NotificationHistoryService.DEFAULT_MAX_ENTRIES).isEqualTo(200);
    }
}
