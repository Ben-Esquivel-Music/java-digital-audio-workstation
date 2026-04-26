package com.benesquivelmusic.daw.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectTest {

    @Test
    void empty_isReallyEmpty() {
        Project p = Project.empty("untitled");
        assertThat(p.tracks()).isEmpty();
        assertThat(p.audioClips()).isEmpty();
        assertThat(p.midiClips()).isEmpty();
        assertThat(p.mixerChannels()).isEmpty();
        assertThat(p.returns()).isEmpty();
        assertThat(p.automationLanes()).isEmpty();
    }

    @Test
    void putAndRemove_returnNewSnapshots_originalUntouched() {
        Project p0 = Project.empty("s");
        Track t = Track.of("Vocals", TrackType.AUDIO);

        Project p1 = p0.putTrack(t);
        assertThat(p0.tracks()).isEmpty();
        assertThat(p1.tracks()).containsEntry(t.id(), t);

        Track t2 = t.withVolume(0.5);
        Project p2 = p1.putTrack(t2);
        assertThat(p1.tracks().get(t.id()).volume()).isEqualTo(1.0);
        assertThat(p2.tracks().get(t.id()).volume()).isEqualTo(0.5);

        Project p3 = p2.removeTrack(t.id());
        assertThat(p3.tracks()).isEmpty();
        assertThat(p2.tracks()).hasSize(1);
    }

    @Test
    void mapsAreImmutable() {
        Project p = Project.empty("s")
                .putTrack(Track.of("T", TrackType.AUDIO))
                .putAudioClip(AudioClip.of("c", 0.0, 1.0, null));

        assertThatThrownBy(() -> p.tracks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> p.audioClips().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void inputMapsAreDefensivelyCopied() {
        Map<UUID, Track> input = new HashMap<>();
        Track t = Track.of("X", TrackType.AUDIO);
        input.put(t.id(), t);

        Project p = Project.empty("s").withTracks(input);
        input.clear(); // should not affect the project's view
        assertThat(p.tracks()).hasSize(1).containsEntry(t.id(), t);
    }

    @Test
    void structuralEquality_acrossSeparateButEqualSnapshots() {
        Track t = Track.of("T", TrackType.AUDIO);
        Project a = Project.empty("s").putTrack(t).withId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        Project b = Project.empty("s").putTrack(t).withId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void concurrentReadsOfSnapshot_areLockFreeAndConsistent() throws Exception {
        // Build a moderately populated project once, then verify that many
        // concurrent reads observe the same field-by-field state. Because
        // the project is immutable this is impossible to break, but the
        // test pins the contract.
        Project base = Project.empty("s");
        for (int i = 0; i < 50; i++) {
            base = base.putTrack(Track.of("T" + i, TrackType.AUDIO));
        }
        Project finalSnapshot = base;

        AtomicInteger mismatches = new AtomicInteger();
        IntStream.range(0, 32).parallel().forEach(_ -> {
            for (int i = 0; i < 1_000; i++) {
                if (finalSnapshot.tracks().size() != 50) {
                    mismatches.incrementAndGet();
                }
            }
        });

        assertThat(mismatches.get()).isZero();
    }
}
