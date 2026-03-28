package com.benesquivelmusic.daw.app.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory store for notification history entries.
 *
 * <p>All notifications shown via {@link NotificationBar} are recorded here.
 * Only {@link NotificationLevel#WARNING} and {@link NotificationLevel#ERROR}
 * entries are retained for the history panel; info/success entries are
 * transient and not stored.</p>
 *
 * <p>Listeners are notified whenever a new entry is added so that the
 * {@link NotificationHistoryPanel} can refresh its display.</p>
 */
public final class NotificationHistoryService {

    /** Default maximum number of retained history entries. */
    static final int DEFAULT_MAX_ENTRIES = 200;

    private final int maxEntries;
    private final List<NotificationEntry> entries = new ArrayList<>();
    private final List<Consumer<NotificationEntry>> listeners = new CopyOnWriteArrayList<>();

    public NotificationHistoryService() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public NotificationHistoryService(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    /**
     * Records a notification. Only warnings and errors are retained in
     * history; info/success notifications are silently ignored.
     *
     * @param level   the notification severity level
     * @param message the notification message text
     */
    public void record(NotificationLevel level, String message) {
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(message, "message must not be null");

        if (level != NotificationLevel.WARNING && level != NotificationLevel.ERROR) {
            return;
        }

        NotificationEntry entry = new NotificationEntry(Instant.now(), level, message);
        synchronized (entries) {
            entries.add(entry);
            if (entries.size() > maxEntries) {
                entries.removeFirst();
            }
        }
        for (Consumer<NotificationEntry> listener : listeners) {
            listener.accept(entry);
        }
    }

    /**
     * Returns an unmodifiable snapshot of all retained history entries,
     * ordered oldest-first.
     */
    public List<NotificationEntry> getEntries() {
        synchronized (entries) {
            return Collections.unmodifiableList(new ArrayList<>(entries));
        }
    }

    /**
     * Returns the number of retained history entries.
     */
    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /**
     * Clears all retained history entries and notifies listeners with a
     * {@code null} entry to signal a full clear.
     */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
        for (Consumer<NotificationEntry> listener : listeners) {
            listener.accept(null);
        }
    }

    /**
     * Registers a listener that is notified whenever a new entry is recorded
     * or when the history is cleared ({@code null} entry).
     */
    public void addListener(Consumer<NotificationEntry> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    /**
     * Removes a previously registered listener.
     */
    public void removeListener(Consumer<NotificationEntry> listener) {
        listeners.remove(listener);
    }
}
