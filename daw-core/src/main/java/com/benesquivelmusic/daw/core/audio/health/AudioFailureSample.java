package com.benesquivelmusic.daw.core.audio.health;

import java.util.Objects;
import java.util.Optional;

/**
 * The drain side's copy of one descriptor a render thread recorded (story
 * 337, rulings E21 and E22). Descriptors are SAMPLES: a lane's ring keeps at
 * most 16 and drops the newest when full, so the counts of
 * {@link AudioHealthRecord#totals()} — never the samples — are the exact
 * numbers. A sample names the failure and the offender behind a count.
 *
 * @param category the failure category
 * @param failure  the thrown object, when the site had one
 * @param subject  the site's subject — a constant, or an object already
 *                 reachable from the site (a plugin host, an automation
 *                 target, the stream generation of a halt)
 * @param detail   a site-specific number: the fault streak of a halt, a task
 *                 index, a segment index, a note number, a bus count
 * @param stamp    the recording lane's heartbeat count when the descriptor
 *                 was written
 * @param lane     the role of the lane that recorded it
 */
public record AudioFailureSample(AudioFailureCategory category,
                                 Optional<Throwable> failure,
                                 Optional<Object> subject,
                                 int detail,
                                 long stamp,
                                 LaneRole lane) {

    public AudioFailureSample {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(lane, "lane must not be null");
    }
}
