package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;

import java.time.Instant;
import java.util.Objects;

/**
 * The latched engine-fault fact (story 337, ruling E41): set when a
 * fingerprint of the audio health record fails in three consecutive
 * one-second buckets, when the render drive halts, or when a backend input
 * thread ends on a fault (an input halt), and cleared by a successful restart
 * (story 337, ruling E62) or a fault-free second on a render drive started
 * after the latch, a reopened or resumed stream (rulings E59 and E63). The
 * audio health drain owns it as a read-only observable; story 338's engine
 * state cell renders it. It is not an {@code EngineState} constant — one name
 * per thing (Failure Surfacing book §3.4).
 *
 * @param category  the failing category
 * @param count     the occurrences counted for its fingerprint so far
 * @param firstSeen when the fingerprint was first seen
 * @param lastSeen  when it last occurred
 * @param action    the action the fact offers (story 337, ruling E13)
 */
public record EngineFault(AudioFailureCategory category, long count, Instant firstSeen, Instant lastSeen,
                          NoticeAction action) {

    public EngineFault {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(firstSeen, "firstSeen must not be null");
        Objects.requireNonNull(lastSeen, "lastSeen must not be null");
        Objects.requireNonNull(action, "action must not be null");
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1: " + count);
        }
    }
}
