package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Append-only audit log written next to a project file. Used by
 * {@link ProjectLockManager} to record lock-related events such as
 * "lock acquired", "lock taken over from {user@host}", and "stale lock
 * recovered".
 *
 * <p>The log is a plain UTF-8 text file (one event per line) so that it is
 * trivially diffable and survives being opened on any platform.</p>
 */
public final class ProjectLog {

    static final String LOG_FILE_NAME = "project.log";

    private final Path logFile;

    public ProjectLog(Path projectDirectory) {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        this.logFile = projectDirectory.resolve(LOG_FILE_NAME);
    }

    /** Appends a single line to the project log, prefixed with an ISO-8601 timestamp. */
    public synchronized void append(Instant when, String message) throws IOException {
        Objects.requireNonNull(when, "when must not be null");
        Objects.requireNonNull(message, "message must not be null");
        String line = when.toString() + " " + message.replace('\n', ' ') + System.lineSeparator();
        Files.writeString(logFile, line,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    /** Returns the path to the log file (which may not yet exist). */
    public Path path() {
        return logFile;
    }

    /** Reads all log lines, or an empty list if the file does not exist. */
    public List<String> readAll() throws IOException {
        if (!Files.exists(logFile)) {
            return List.of();
        }
        return Files.readAllLines(logFile);
    }
}
