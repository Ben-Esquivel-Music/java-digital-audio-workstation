package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureSample;

import java.util.ArrayList;
import java.util.List;

/** One kept subject of a one-shot category in the {@link AudioHealthDrain}, with its kept samples, oldest first. */
final class OneShotSubject {
    final String key;
    final List<AudioFailureSample> samples = new ArrayList<>();

    OneShotSubject(String key) {
        this.key = key;
    }

    /** The samples a report that counted {@code counted} occurrences of this subject uses: the oldest ones. */
    List<AudioFailureSample> used(long counted) {
        return List.copyOf(samples.subList(0, (int) Math.min(counted, samples.size())));
    }
}
