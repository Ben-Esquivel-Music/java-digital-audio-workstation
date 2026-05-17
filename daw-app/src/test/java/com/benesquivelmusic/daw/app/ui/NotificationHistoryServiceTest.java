package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationHistoryServiceTest {

    private NotificationHistoryService service;

    @BeforeEach
    void setUp() {
        service = new NotificationHistoryService();
    }

    /**
     * Test-local convenience for the action-less record path. Production
     * always calls the canonical four-argument {@code record} (story 273);
     * only tests want the short form, so the convenience lives here.
     */
    private static void record(NotificationHistoryService svc,
                               NotificationLevel level,
                               String message) {
        svc.record(level, message, Optional.empty(), Optional.empty());
    }

    @Test
    void shouldStartEmpty() {
        assertThat(service.size()).isZero();
        assertThat(service.getEntries()).isEmpty();
    }

    @Test
    void shouldRecordWarningEntries() {
        record(service, NotificationLevel.WARNING, "Low disk space");

        assertThat(service.size()).isEqualTo(1);
        NotificationEntry entry = service.getEntries().getFirst();
        assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(entry.message()).isEqualTo("Low disk space");
        assertThat(entry.timestamp()).isNotNull();
    }

    @Test
    void shouldRecordErrorEntries() {
        record(service, NotificationLevel.ERROR, "Save failed");

        assertThat(service.size()).isEqualTo(1);
        NotificationEntry entry = service.getEntries().getFirst();
        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(entry.message()).isEqualTo("Save failed");
    }

    @Test
    void shouldRetainInfoEntries() {
        // Story 273 — the history is the single notification log; all
        // levels are retained, not just warnings/errors.
        record(service, NotificationLevel.INFO, "Informational");

        assertThat(service.size()).isEqualTo(1);
        assertThat(service.getEntries().getFirst().level())
                .isEqualTo(NotificationLevel.INFO);
    }

    @Test
    void shouldRetainSuccessEntries() {
        record(service, NotificationLevel.SUCCESS, "Saved ok");

        assertThat(service.size()).isEqualTo(1);
        assertThat(service.getEntries().getFirst().level())
                .isEqualTo(NotificationLevel.SUCCESS);
    }

    @Test
    void shouldRetainOrderOldestFirst() {
        record(service, NotificationLevel.ERROR, "first");
        record(service, NotificationLevel.WARNING, "second");
        record(service, NotificationLevel.ERROR, "third");

        List<NotificationEntry> entries = service.getEntries();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).message()).isEqualTo("first");
        assertThat(entries.get(1).message()).isEqualTo("second");
        assertThat(entries.get(2).message()).isEqualTo("third");
    }

    @Test
    void shouldEvictOldestWhenMaxExceeded() {
        NotificationHistoryService small = new NotificationHistoryService(3);
        record(small, NotificationLevel.ERROR, "a");
        record(small, NotificationLevel.ERROR, "b");
        record(small, NotificationLevel.ERROR, "c");
        record(small, NotificationLevel.ERROR, "d");

        assertThat(small.size()).isEqualTo(3);
        List<NotificationEntry> entries = small.getEntries();
        assertThat(entries.get(0).message()).isEqualTo("b");
        assertThat(entries.get(1).message()).isEqualTo("c");
        assertThat(entries.get(2).message()).isEqualTo("d");
    }

    @Test
    void clearShouldRemoveAllEntries() {
        record(service, NotificationLevel.ERROR, "error1");
        record(service, NotificationLevel.WARNING, "warn1");
        service.clear();

        assertThat(service.size()).isZero();
        assertThat(service.getEntries()).isEmpty();
    }

    @Test
    void shouldNotifyListenerOnRecord() {
        List<NotificationEntry> received = new ArrayList<>();
        service.addListener(received::add);

        record(service, NotificationLevel.ERROR, "new error");

        assertThat(received).hasSize(1);
        assertThat(received.getFirst().message()).isEqualTo("new error");
    }

    @Test
    void shouldNotifyListenerWithNullOnClear() {
        List<NotificationEntry> received = new ArrayList<>();
        service.addListener(received::add);

        record(service, NotificationLevel.ERROR, "error");
        service.clear();

        assertThat(received).hasSize(2);
        assertThat(received.get(0).message()).isEqualTo("error");
        assertThat(received.get(1)).isNull();
    }

    @Test
    void shouldNotifyListenerForAllLevels() {
        // Story 273 — listeners fire for every recorded entry regardless
        // of level (the section must show info/success too).
        AtomicInteger callCount = new AtomicInteger();
        service.addListener(_ -> callCount.incrementAndGet());

        record(service, NotificationLevel.INFO, "info");
        record(service, NotificationLevel.SUCCESS, "success");

        assertThat(callCount.get()).isEqualTo(2);
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
        record(service, NotificationLevel.ERROR, "first");
        assertThat(callCount.get()).isEqualTo(1);

        service.removeListener(listener);
        record(service, NotificationLevel.ERROR, "second");
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
        assertThatThrownBy(() -> record(service, null, "msg"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullMessage() {
        assertThatThrownBy(() -> record(service, NotificationLevel.ERROR, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullListener() {
        assertThatThrownBy(() -> service.addListener(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getEntriesShouldReturnUnmodifiableList() {
        record(service, NotificationLevel.ERROR, "test");
        List<NotificationEntry> entries = service.getEntries();

        assertThatThrownBy(() -> entries.add(new NotificationEntry(
                java.time.Instant.now(), NotificationLevel.ERROR, "hack",
                Optional.empty(), Optional.empty())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaultMaxEntriesShouldBe100() {
        // Story 273 — the section shows the most recent ~100.
        assertThat(NotificationHistoryService.DEFAULT_MAX_ENTRIES).isEqualTo(100);
    }
}
