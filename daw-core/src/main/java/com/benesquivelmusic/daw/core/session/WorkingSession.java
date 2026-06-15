package com.benesquivelmusic.daw.core.session;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A first-class working session — the §3.3 object from the Project Manager
 * Design Book.
 *
 * <p>A working session is the unit of work a user does in one sitting: "I sat
 * down at 9&nbsp;a.m. and worked until 8&nbsp;p.m." Until now this was implicit
 * in checkpoint timestamps (design book §1.9); this type makes it durable,
 * named, and navigable. A session can be renamed, compared, exported, and
 * rolled back to.</p>
 *
 * <p>The unqualified word <em>Session</em> always means this working-session
 * object. The DAWproject interchange payload — formerly also called a
 * "session" — is surfaced in the UI as <strong>"DAWproject Exchange"</strong>
 * (design book §1.7 / §2.4).</p>
 *
 * <p>A session is a shallowly-immutable, transparent data carrier (JEP 395).
 * Mutating operations return a new instance via the {@code with*} methods, so
 * instances can be shared freely across threads.</p>
 *
 * @param id               stable identifier (defaults to a random UUID)
 * @param name             user-facing name; defaults to
 *                         {@code "Working session — <date>"}
 * @param startTime        the instant the session was opened
 * @param endTime          the instant the session was sealed, or {@code null}
 *                         while the session is still active
 * @param recordedTime     total recorded time, idle gaps excluded
 * @param takeIds          identifiers of takes recorded during this session
 * @param checkpointIds    identifiers of checkpoints written during this session
 * @param journalSegments  journal segment file names sealed by this session
 * @param notes            free-text notes the user types in the Session Manager
 */
public record WorkingSession(
        String id,
        String name,
        Instant startTime,
        Instant endTime,
        Duration recordedTime,
        List<String> takeIds,
        List<String> checkpointIds,
        List<String> journalSegments,
        String notes) {

    private static final DateTimeFormatter DEFAULT_NAME_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Canonical constructor. Validates required fields and defensively copies
     * the link lists so the record is deeply immutable.
     */
    public WorkingSession {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(recordedTime, "recordedTime must not be null");
        if (recordedTime.isNegative()) {
            throw new IllegalArgumentException("recordedTime must not be negative");
        }
        if (endTime != null && endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("endTime must not precede startTime");
        }
        takeIds = takeIds == null ? List.of() : List.copyOf(takeIds);
        checkpointIds = checkpointIds == null ? List.of() : List.copyOf(checkpointIds);
        journalSegments = journalSegments == null ? List.of() : List.copyOf(journalSegments);
        notes = notes == null ? "" : notes;
    }

    /**
     * Starts a fresh, active session with a generated identifier and the
     * default name for the start date.
     *
     * @param startTime the instant the session begins
     * @param zone      the zone used to derive the default name's date
     * @return a new active session with no linked artifacts
     */
    public static WorkingSession start(Instant startTime, ZoneId zone) {
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(zone, "zone must not be null");
        return new WorkingSession(
                UUID.randomUUID().toString(),
                defaultName(LocalDate.ofInstant(startTime, zone)),
                startTime,
                null,
                Duration.ZERO,
                List.of(),
                List.of(),
                List.of(),
                "");
    }

    /**
     * The default session name for a given date, e.g. {@code "Working session
     * — 2026-03-21"}.
     *
     * @param date the start date
     * @return the default, user-renamable name
     */
    public static String defaultName(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        return "Working session \u2014 " + DEFAULT_NAME_DATE.format(date);
    }

    /** {@return {@code true} if this session has not yet been sealed} */
    public boolean isActive() {
        return endTime == null;
    }

    /**
     * The session's wall-clock span from start to end (or to {@code now} while
     * still active).
     *
     * @param now the reference instant for an active session
     * @return the elapsed wall-clock duration, never negative
     */
    public Duration elapsed(Instant now) {
        Instant end = endTime != null ? endTime : Objects.requireNonNull(now, "now must not be null");
        Duration span = Duration.between(startTime, end);
        return span.isNegative() ? Duration.ZERO : span;
    }

    /** {@return a copy of this session renamed to {@code newName}} */
    public WorkingSession withName(String newName) {
        Objects.requireNonNull(newName, "newName must not be null");
        return new WorkingSession(id, newName, startTime, endTime, recordedTime,
                takeIds, checkpointIds, journalSegments, notes);
    }

    /** {@return a copy of this session with {@code notes} replaced} */
    public WorkingSession withNotes(String newNotes) {
        return new WorkingSession(id, name, startTime, endTime, recordedTime,
                takeIds, checkpointIds, journalSegments, newNotes);
    }

    /** {@return a copy of this session with {@code recordedTime} replaced} */
    public WorkingSession withRecordedTime(Duration newRecordedTime) {
        return new WorkingSession(id, name, startTime, endTime, newRecordedTime,
                takeIds, checkpointIds, journalSegments, notes);
    }

    /**
     * Seals this session at {@code endInstant}, marking it closed.
     *
     * @param endInstant the instant the session ends; must not precede the start
     * @return a closed copy of this session
     */
    public WorkingSession sealed(Instant endInstant) {
        Objects.requireNonNull(endInstant, "endInstant must not be null");
        return new WorkingSession(id, name, startTime, endInstant, recordedTime,
                takeIds, checkpointIds, journalSegments, notes);
    }

    /** {@return a copy of this session with {@code takeId} appended} */
    public WorkingSession withTake(String takeId) {
        return new WorkingSession(id, name, startTime, endTime, recordedTime,
                append(takeIds, takeId), checkpointIds, journalSegments, notes);
    }

    /** {@return a copy of this session with {@code checkpointId} appended} */
    public WorkingSession withCheckpoint(String checkpointId) {
        return new WorkingSession(id, name, startTime, endTime, recordedTime,
                takeIds, append(checkpointIds, checkpointId), journalSegments, notes);
    }

    /** {@return a copy of this session with {@code segment} appended} */
    public WorkingSession withJournalSegment(String segment) {
        return new WorkingSession(id, name, startTime, endTime, recordedTime,
                takeIds, checkpointIds, append(journalSegments, segment), notes);
    }

    private static List<String> append(List<String> base, String value) {
        Objects.requireNonNull(value, "value must not be null");
        var copy = new java.util.ArrayList<String>(base.size() + 1);
        copy.addAll(base);
        copy.add(value);
        return List.copyOf(copy);
    }
}
