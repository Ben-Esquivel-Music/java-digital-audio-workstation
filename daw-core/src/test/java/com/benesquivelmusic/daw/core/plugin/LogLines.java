package com.benesquivelmusic.daw.core.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The messages one class's logger publishes while this is open, by level;
 * closing it removes its handler — for the registry tests' exact lines.
 */
final class LogLines implements AutoCloseable {

    private final Logger logger;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private LogLines(Class<?> owner) {
        logger = Logger.getLogger(owner.getName());
        logger.addHandler(handler);
    }

    /** Starts capturing what {@code owner}'s logger publishes. */
    static LogLines of(Class<?> owner) {
        return new LogLines(owner);
    }

    /** The messages published at {@code level}, in order. */
    List<String> at(Level level) {
        List<String> messages = new ArrayList<>();
        for (LogRecord record : records) {
            if (record.getLevel() == level) {
                messages.add(record.getMessage());
            }
        }
        return messages;
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
    }
}
