package com.benesquivelmusic.daw.app.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory store for notification history entries — the single
 * notification log (story 273).
 *
 * <p>Every notification shown via {@link NotificationBar} is recorded
 * here, regardless of {@link NotificationLevel}. The transient toast and
 * the inspector Notifications section are both derived from this one
 * model: the toast is the <em>transient</em> surface, the section is the
 * <em>log</em>. Only the most recent {@link #DEFAULT_MAX_ENTRIES} entries
 * are retained; the section shows them grouped by day.</p>
 *
 * <p>Listeners are notified whenever a new entry is added (or when the
 * history is cleared, signalled by a {@code null} entry) so that the
 * inspector Notifications section can refresh its display.</p>
 */
public final class NotificationHistoryService {

    /** Default maximum number of retained history entries (story 273: most recent ~100). */
    static final int DEFAULT_MAX_ENTRIES = 100;

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
     * Records a notification, optionally carrying an action so the
     * history pill can re-trigger it.
     *
     * @param level       the notification severity level
     * @param message     the notification message text
     * @param action      optional action to re-trigger from the history pill
     * @param actionLabel optional label for {@code action}
     */
    public void record(NotificationLevel level,
                        String message,
                        Optional<Runnable> action,
                        Optional<String> actionLabel) {
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(message, "message must not be null");

        NotificationEntry entry =
                new NotificationEntry(Instant.now(), level, message, action, actionLabel);
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
