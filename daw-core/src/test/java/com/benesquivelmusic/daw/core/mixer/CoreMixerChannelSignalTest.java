package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.mixer.MixerChannel.ChangeKind;

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
 * Story 291 — verifies the {@link MixerChannel} toolkit-neutral
 * change-notification seam (Control Synchronization Design Book §1.3, §2.5,
 * §3.2). The seam fixes the "silent model" problem: the channel now emits a
 * neutral {@code Consumer<ChangeKind>} signal so the view-model can mirror it.
 *
 * <p>This test lives in {@code daw-core}, which has <strong>no JavaFX on the
 * classpath</strong> — the fact that it compiles and runs is itself the proof
 * that the seam is toolkit-neutral (no {@code javafx.beans.*} leaked into the
 * core, §9 rejection list). It asserts on {@link ChangeKind} callbacks only,
 * never on rendered output.</p>
 */
class CoreMixerChannelSignalTest {

    private static MixerChannel newChannel() {
        return new MixerChannel("Channel 1");
    }

    @Test
    void muteSignalFiresAfterTheFieldChangesSoTheObserverReadsThePostMutationValue() {
        MixerChannel channel = newChannel();
        List<Boolean> observed = new ArrayList<>();
        channel.addChangeListener(kind -> {
            if (kind == ChangeKind.MUTE) {
                // Re-reading inside the callback must yield the POST-mutation value.
                observed.add(channel.isMuted());
            }
        });

        channel.setMuted(true);
        channel.setMuted(false);

        assertThat(observed)
                .as("each MUTE signal fires after the field flips, so the observer reads the new state")
                .containsExactly(true, false);
    }

    @Test
    void volumeSignalCarriesThePostMutationVolume() {
        MixerChannel channel = newChannel();
        List<Double> observed = new ArrayList<>();
        channel.addChangeListener(kind -> {
            if (kind == ChangeKind.VOLUME) {
                observed.add(channel.getVolume());
            }
        });

        channel.setVolume(0.5);

        assertThat(observed).containsExactly(0.5);
    }

    @Test
    void panSignalCarriesThePostMutationPan() {
        MixerChannel channel = newChannel();
        List<Double> observed = new ArrayList<>();
        channel.addChangeListener(kind -> {
            if (kind == ChangeKind.PAN) {
                observed.add(channel.getPan());
            }
        });

        channel.setPan(0.75);

        assertThat(observed).containsExactly(0.75);
    }

    @Test
    void soloSignalCarriesThePostMutationSolo() {
        MixerChannel channel = newChannel();
        List<Boolean> observed = new ArrayList<>();
        channel.addChangeListener(kind -> {
            if (kind == ChangeKind.SOLO) {
                observed.add(channel.isSolo());
            }
        });

        channel.setSolo(true);

        assertThat(observed).containsExactly(true);
    }

    @Test
    void eachMutationFiresExactlyOnceWithItsMatchingKind() {
        MixerChannel channel = newChannel();
        Map<ChangeKind, AtomicInteger> counts = new EnumMap<>(ChangeKind.class);
        for (ChangeKind kind : ChangeKind.values()) {
            counts.put(kind, new AtomicInteger());
        }
        channel.addChangeListener(kind -> counts.get(kind).incrementAndGet());

        channel.setVolume(0.8); // VOLUME
        channel.setPan(0.3);    // PAN
        channel.setMuted(true); // MUTE
        channel.setSolo(true);  // SOLO

        assertThat(counts.get(ChangeKind.VOLUME).get()).isEqualTo(1);
        assertThat(counts.get(ChangeKind.PAN).get()).isEqualTo(1);
        assertThat(counts.get(ChangeKind.MUTE).get()).isEqualTo(1);
        assertThat(counts.get(ChangeKind.SOLO).get()).isEqualTo(1);
    }

    @Test
    void nonSignallingSettersDoNotFire() {
        // Only the four documented slices signal; sibling setters such as
        // setSoloSafe / setSendLevel / setColor are deliberately silent.
        MixerChannel channel = newChannel();
        AtomicInteger fires = new AtomicInteger();
        channel.addChangeListener(kind -> fires.incrementAndGet());

        channel.setSoloSafe(true);
        channel.setSendLevel(0.4);
        channel.setColor(null);
        channel.setPhaseInverted(true);

        assertThat(fires.get())
                .as("setters outside the four-slice ChangeKind set must not fire a signal")
                .isZero();
    }

    @Test
    void removedListenerStopsReceivingSignals() {
        MixerChannel channel = newChannel();
        AtomicInteger fires = new AtomicInteger();
        Runnable token = channel.addChangeListener(kind -> fires.incrementAndGet());

        channel.setMuted(true);
        token.run(); // unregister
        channel.setMuted(false);

        assertThat(fires.get())
                .as("after the removal token runs, the listener receives no further signals")
                .isEqualTo(1);
    }

    @Test
    void removeChangeListenerAlsoUnregisters() {
        MixerChannel channel = newChannel();
        AtomicInteger fires = new AtomicInteger();
        var listener = (java.util.function.Consumer<ChangeKind>) kind -> fires.incrementAndGet();
        channel.addChangeListener(listener);

        channel.setMuted(true);
        channel.removeChangeListener(listener);
        channel.setMuted(false);

        assertThat(fires.get()).isEqualTo(1);
    }

    @Test
    void aListenerRemovingAnotherDuringNotificationDoesNotThrowAndKeepsSnapshotSemantics() {
        MixerChannel channel = newChannel();
        List<ChangeKind> secondSaw = new ArrayList<>();

        // The first listener unregisters the second one *during* notification. Under the
        // old cached-size + get(i) loop this shrank the backing list, so the next get(i)
        // read a now-shorter array and threw IndexOutOfBoundsException. The snapshot array
        // captured once at notify start makes the in-flight notify immune to the removal.
        AtomicReference<Runnable> removeSecond = new AtomicReference<>();
        channel.addChangeListener(kind -> {
            Runnable token = removeSecond.getAndSet(null);
            if (token != null) {
                token.run(); // reentrant remove of the *second* listener
            }
        });
        removeSecond.set(channel.addChangeListener(secondSaw::add));

        channel.setMuted(true); // must not throw IndexOutOfBoundsException

        assertThat(secondSaw)
                .as("the snapshot captured at notify start is stable: a listener removed "
                        + "mid-notify still fires once this round")
                .containsExactly(ChangeKind.MUTE);

        secondSaw.clear();
        channel.setMuted(false);

        assertThat(secondSaw)
                .as("after the reentrant removal, the second listener receives no further signals")
                .isEmpty();
    }

    @Test
    void registrationRejectsNullListener() {
        MixerChannel channel = newChannel();
        assertThatNullPointerException().isThrownBy(() -> channel.addChangeListener(null));
        assertThatNullPointerException().isThrownBy(() -> channel.removeChangeListener(null));
    }
}
