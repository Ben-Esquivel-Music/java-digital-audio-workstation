package com.benesquivelmusic.daw.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A real {@link NotificationManager} for engine-controller tests (story 338's
 * injected-sink rule: never {@code noop()} where a notice is the fact): it
 * keeps every notice in order with its action, the thread that sent it, and
 * whatever {@link #context} reads at that moment on that thread.
 */
final class RecordingNotifications implements NotificationManager {

    /**
     * One notice.
     *
     * @param message     the text
     * @param actionLabel the action's label, when it has one
     * @param action      the action, when it has one
     * @param thread      the name of the thread that sent it
     * @param context     what {@link #context} read when it was sent
     */
    record Notice(String message, Optional<String> actionLabel, Optional<Runnable> action, String thread,
                  Object context) {
    }

    private final List<Notice> notices = new ArrayList<>();
    private volatile Supplier<Object> context = () -> null;

    /** What each notice records about the moment it was sent, read on the sending thread. */
    void context(Supplier<Object> reader) {
        this.context = reader;
    }

    @Override
    public void notify(String message) {
        add(message, Optional.empty(), Optional.empty());
    }

    @Override
    public void notify(String message, String actionLabel, Runnable action) {
        add(message, Optional.of(actionLabel), Optional.of(action));
    }

    private void add(String message, Optional<String> actionLabel, Optional<Runnable> action) {
        Notice notice = new Notice(message, actionLabel, action, Thread.currentThread().getName(),
                context.get());
        synchronized (notices) {
            notices.add(notice);
        }
    }

    List<Notice> notices() {
        synchronized (notices) {
            return List.copyOf(notices);
        }
    }

    List<String> messages() {
        return notices().stream().map(Notice::message).toList();
    }

    int size() {
        synchronized (notices) {
            return notices.size();
        }
    }
}
