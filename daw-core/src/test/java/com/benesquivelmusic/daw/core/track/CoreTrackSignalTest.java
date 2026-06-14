package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.track.Track.ChangeKind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Story 291 — verifies the {@link Track} toolkit-neutral change-notification
 * seam (Control Synchronization Design Book §1.3, §2.5, §3.2). The seam fixes
 * the "silent model" problem: the track now emits a neutral
 * {@code Consumer<ChangeKind>} signal so the view-model can mirror it.
 *
 * <p>This test lives in {@code daw-core}, which has <strong>no JavaFX on the
 * classpath</strong> — the fact that it compiles and runs is itself the proof
 * that the seam is toolkit-neutral (no {@code javafx.beans.*} leaked into the
 * core, §9 rejection list). It asserts on {@link ChangeKind} callbacks only,
 * never on rendered output.</p>
 */
class CoreTrackSignalTest {

    private static Track newTrack() {
        return new Track("Drums", TrackType.AUDIO);
    }

    @Test
    void muteSignalFiresAfterTheFieldChangesSoTheObserverReadsThePostMutationValue() {
        Track track = newTrack();
        List<Boolean> observed = new ArrayList<>();
        track.addChangeListener(kind -> {
            if (kind == ChangeKind.MUTE) {
                // Re-reading inside the callback must yield the POST-mutation value.
                observed.add(track.isMuted());
            }
        });

        track.setMuted(true);
        track.setMuted(false);

        assertThat(observed)
                .as("each MUTE signal fires after the field flips, so the observer reads the new state")
                .containsExactly(true, false);
    }

    @Test
    void nameSignalCarriesThePostMutationName() {
        Track track = newTrack();
        List<String> observed = new ArrayList<>();
        track.addChangeListener(kind -> {
            if (kind == ChangeKind.NAME) {
                observed.add(track.getName());
            }
        });

        track.setName("Lead Vocal");

        assertThat(observed).containsExactly("Lead Vocal");
    }

    @Test
    void volumeSignalCarriesThePostMutationVolume() {
        Track track = newTrack();
        List<Double> observed = new ArrayList<>();
        track.addChangeListener(kind -> {
            if (kind == ChangeKind.VOLUME) {
                observed.add(track.getVolume());
            }
        });

        track.setVolume(0.5);

        assertThat(observed).containsExactly(0.5);
    }

    @Test
    void panSignalCarriesThePostMutationPan() {
        Track track = newTrack();
        List<Double> observed = new ArrayList<>();
        track.addChangeListener(kind -> {
            if (kind == ChangeKind.PAN) {
                observed.add(track.getPan());
            }
        });

        track.setPan(-0.25);

        assertThat(observed).containsExactly(-0.25);
    }

    @Test
    void eachMutationFiresExactlyOnceWithItsMatchingKind() {
        Track track = newTrack();
        Map<ChangeKind, AtomicInteger> counts = new EnumMap<>(ChangeKind.class);
        for (ChangeKind kind : ChangeKind.values()) {
            counts.put(kind, new AtomicInteger());
        }
        track.addChangeListener(kind -> counts.get(kind).incrementAndGet());

        track.setName("Bass");   // NAME
        track.setVolume(0.8);    // VOLUME
        track.setPan(0.3);       // PAN
        track.setMuted(true);    // MUTE
        track.setSolo(true);     // SOLO
        track.setArmed(true);    // ARM

        assertThat(counts.get(ChangeKind.NAME).get()).isEqualTo(1);
        assertThat(counts.get(ChangeKind.VOLUME).get()).isEqualTo(1);
        assertThat(counts.get(ChangeKind.PAN).get()).isEqualTo(1);
        assertThat(counts.get(ChangeKind.MUTE).get()).isEqualTo(1);
        assertThat(counts.get(ChangeKind.SOLO).get()).isEqualTo(1);
        assertThat(counts.get(ChangeKind.ARM).get()).isEqualTo(1);
    }

    @Test
    void soloAndArmSignalsCarryTheirPostMutationValues() {
        Track track = newTrack();
        List<Boolean> soloSeen = new ArrayList<>();
        List<Boolean> armSeen = new ArrayList<>();
        track.addChangeListener(kind -> {
            switch (kind) {
                case SOLO -> soloSeen.add(track.isSolo());
                case ARM -> armSeen.add(track.isArmed());
                default -> { /* other slices not under test */ }
            }
        });

        track.setSolo(true);
        track.setArmed(true);

        assertThat(soloSeen).containsExactly(true);
        assertThat(armSeen).containsExactly(true);
    }

    @Test
    void removedListenerStopsReceivingSignals() {
        Track track = newTrack();
        AtomicInteger fires = new AtomicInteger();
        Runnable token = track.addChangeListener(kind -> fires.incrementAndGet());

        track.setMuted(true);
        token.run(); // unregister
        track.setMuted(false);

        assertThat(fires.get())
                .as("after the removal token runs, the listener receives no further signals")
                .isEqualTo(1);
    }

    @Test
    void removeChangeListenerAlsoUnregisters() {
        Track track = newTrack();
        AtomicInteger fires = new AtomicInteger();
        var listener = (java.util.function.Consumer<ChangeKind>) kind -> fires.incrementAndGet();
        track.addChangeListener(listener);

        track.setMuted(true);
        track.removeChangeListener(listener);
        track.setMuted(false);

        assertThat(fires.get()).isEqualTo(1);
    }

    @Test
    void aListenerRemovingAnotherDuringNotificationDoesNotThrowAndKeepsSnapshotSemantics() {
        Track track = newTrack();
        List<ChangeKind> secondSaw = new ArrayList<>();

        // The first listener unregisters the second one *during* notification. Under the
        // old cached-size + get(i) loop this shrank the backing list, so the next get(i)
        // read a now-shorter array and threw IndexOutOfBoundsException. The snapshot array
        // captured once at notify start makes the in-flight notify immune to the removal.
        AtomicReference<Runnable> removeSecond = new AtomicReference<>();
        track.addChangeListener(kind -> {
            Runnable token = removeSecond.getAndSet(null);
            if (token != null) {
                token.run(); // reentrant remove of the *second* listener
            }
        });
        removeSecond.set(track.addChangeListener(secondSaw::add));

        track.setMuted(true); // must not throw IndexOutOfBoundsException

        assertThat(secondSaw)
                .as("the snapshot captured at notify start is stable: a listener removed "
                        + "mid-notify still fires once this round")
                .containsExactly(ChangeKind.MUTE);

        secondSaw.clear();
        track.setMuted(false);

        assertThat(secondSaw)
                .as("after the reentrant removal, the second listener receives no further signals")
                .isEmpty();
    }

    @Test
    void registrationRejectsNullListener() {
        Track track = newTrack();
        assertThatNullPointerException().isThrownBy(() -> track.addChangeListener(null));
        assertThatNullPointerException().isThrownBy(() -> track.removeChangeListener(null));
    }
}
