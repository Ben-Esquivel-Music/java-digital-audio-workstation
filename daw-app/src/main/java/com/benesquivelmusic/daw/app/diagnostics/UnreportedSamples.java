package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureSample;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The samples the audio health drain has taken from the engine's record and
 * not yet used in a report (story 337, ruling E79). A lane publishes a
 * failure's descriptor before its count, so a sample can reach the drain a
 * reading before the count that announces it; it is kept until a report of
 * its category uses it, and a notice whose sample existed names its subject.
 *
 * <p>A streaming, halt or input-halt report uses every sample of its
 * category ({@link #clear}). A one-shot report uses, per subject it reports,
 * as many samples as it counted for that subject, oldest first
 * ({@link #remove}); the rest stay for the next delta. At most
 * {@link #MAX_PER_CATEGORY} samples are kept per category, the oldest
 * dropped beyond that.</p>
 *
 * <p>FX thread only, like the drain that owns it.</p>
 */
final class UnreportedSamples {

    /** The most samples kept per category; beyond it the oldest is dropped. */
    static final int MAX_PER_CATEGORY = 64;

    private final Map<AudioFailureCategory, ArrayDeque<AudioFailureSample>> kept =
            new EnumMap<>(AudioFailureCategory.class);

    /**
     * Keeps {@code sample} until a report of its category uses it.
     *
     * @param sample the sample the record handed to the drain
     */
    void add(AudioFailureSample sample) {
        Objects.requireNonNull(sample, "sample must not be null");
        ArrayDeque<AudioFailureSample> samples =
                kept.computeIfAbsent(sample.category(), category -> new ArrayDeque<>());
        if (samples.size() >= MAX_PER_CATEGORY) {
            samples.removeFirst();
        }
        samples.addLast(sample);
    }

    /**
     * The kept samples of {@code category}.
     *
     * @param category the category
     * @return a copy, oldest first
     */
    List<AudioFailureSample> of(AudioFailureCategory category) {
        ArrayDeque<AudioFailureSample> samples = kept.get(category);
        return samples == null ? List.of() : List.copyOf(samples);
    }

    /**
     * The newest throwable a kept sample of {@code category} carries.
     *
     * @param category the category
     * @return the throwable, if any sample carries one
     */
    Optional<Throwable> newestFailure(AudioFailureCategory category) {
        Iterator<AudioFailureSample> newestFirst = newestFirst(category);
        while (newestFirst.hasNext()) {
            Optional<Throwable> failure = newestFirst.next().failure();
            if (failure.isPresent()) {
                return failure;
            }
        }
        return Optional.empty();
    }

    /**
     * The newest subject a kept sample of {@code category} names.
     *
     * @param category the category
     * @return the subject, if any sample names one
     */
    Optional<Object> newestSubject(AudioFailureCategory category) {
        Iterator<AudioFailureSample> newestFirst = newestFirst(category);
        while (newestFirst.hasNext()) {
            Optional<Object> subject = newestFirst.next().subject();
            if (subject.isPresent()) {
                return subject;
            }
        }
        return Optional.empty();
    }

    /**
     * Drops every kept sample of {@code category}: a report of it used them.
     *
     * @param category the category
     */
    void clear(AudioFailureCategory category) {
        kept.remove(category);
    }

    /**
     * Drops the samples of {@code category} that a report used, by identity;
     * every other sample stays.
     *
     * @param category the category
     * @param used     the samples the report used
     */
    void remove(AudioFailureCategory category, List<AudioFailureSample> used) {
        ArrayDeque<AudioFailureSample> samples = kept.get(category);
        if (samples == null || used.isEmpty()) {
            return;
        }
        Set<AudioFailureSample> drop = Collections.newSetFromMap(new IdentityHashMap<>());
        drop.addAll(used);
        samples.removeIf(drop::contains);
        if (samples.isEmpty()) {
            kept.remove(category);
        }
    }

    private Iterator<AudioFailureSample> newestFirst(AudioFailureCategory category) {
        ArrayDeque<AudioFailureSample> samples = kept.get(category);
        return samples == null ? Collections.emptyIterator() : samples.descendingIterator();
    }
}
