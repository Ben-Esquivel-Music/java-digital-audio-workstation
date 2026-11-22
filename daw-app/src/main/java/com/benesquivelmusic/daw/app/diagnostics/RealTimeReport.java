package com.benesquivelmusic.daw.app.diagnostics;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The audio health drain's report of one fingerprint for one frame (story
 * 337, rulings E39, E40 and E43): what {@link FailureFunnel#reportRealTime},
 * {@link FailureFunnel#escalateRealTime}, {@link FailureFunnel#foldRealTime}
 * and {@link FailureFunnel#foldIntoDeviceLoss}
 * turn into a failure fact. The drain reads counters the real-time threads
 * recorded (Failure Surfacing book §2.3 "The RT thread records; it never
 * surfaces"), so the report names the guard site rather than the FX thread
 * the drain runs on, and carries the counter delta as a weight.
 *
 * @param origin         which boundary caught the failures (book §3.1)
 * @param severity       how serious the notice is: WARNING for a first occurrence, ERROR or FATAL for a
 *                       sustained fault or a halt
 * @param fingerprint    the dedupe key — {@code category|site} for the streaming categories, or
 *                       {@code category|subject} for the one-shot ones (story 337, ruling E40)
 * @param weight         how many occurrences this frame's counter delta holds; at least 1 (story 337,
 *                       ruling E39)
 * @param newest         the newest sampled throwable, which the notice's reason comes from, if any
 * @param sampledAnchors every distinct anchor sampled since the fingerprint's last notice, for the log
 *                       record only (story 337, ruling E40)
 * @param site           the guard site or real-time thread that recorded the failures, named in the
 *                       log record in place of a thread name
 * @param baseText       the notice's base sentence, already formatted ({@link FailureCopy})
 * @param action         the notice's action (story 337, ruling E13)
 */
public record RealTimeReport(FailureFact.Origin origin, FailureFact.Severity severity, Fingerprint fingerprint,
                             long weight, Optional<Throwable> newest, List<String> sampledAnchors, String site,
                             String baseText, NoticeAction action) {

    public RealTimeReport {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(newest, "newest must not be null");
        Objects.requireNonNull(site, "site must not be null");
        Objects.requireNonNull(baseText, "baseText must not be null");
        Objects.requireNonNull(action, "action must not be null");
        sampledAnchors = List.copyOf(Objects.requireNonNull(sampledAnchors, "sampledAnchors must not be null"));
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be at least 1: " + weight);
        }
    }
}
