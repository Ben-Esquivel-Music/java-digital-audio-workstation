package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;

import java.time.Instant;
import java.util.Objects;

/**
 * The latched engine-fault fact (story 337, ruling E41): set when a
 * fingerprint of a streaming category fails in three consecutive one-second
 * buckets (a one-shot category, {@code audio.automation} or
 * {@code audio.config}, is reported only as a WARNING), when the render
 * drive halts, or when a backend input thread ends on a fault (an input
 * halt), and cleared by a successful restart (story 337, ruling E62) or by a
 * fault-free second on a render drive started after the latch, a reopened or
 * resumed stream (story 337, rulings E59 and E63) — for a render halt, any
 * drive started after the halted one, even one already running when the drain
 * saw the halt (story 337, ruling E71); but once an input halt has escalated,
 * until the fact clears and whatever fact is latched by then, only a drive on
 * a stream opened after the newest such halt (a resume opens no stream; story
 * 337, ruling E87). A sustained run of a device-side category
 * ({@code audio.callback}, {@code .sink}, {@code .pacing}, {@code .input},
 * {@code .device}) sets it only once the stream's heartbeat has advanced
 * since the run began: until then a stalled device is the likelier cause
 * (story 338, ruling E68). Once a device loss reaches the drain, neither
 * such a run nor an input halt sets it on the lost render drive or an older
 * one, and a fact already latched on such a drive is
 * cleared when every category it latched is device-side: an input halt's
 * ({@code audio.input}) is, a render halt's ({@code audio.render.halted})
 * never is (story 338, rulings E69 and E70;
 * {@link AudioHealthDrain#streamLost}). The audio health drain owns it as a
 * read-only observable; story 338's engine state cell renders it. It is not
 * an {@code EngineState} constant — one name per thing (Failure Surfacing
 * book §3.4).
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
