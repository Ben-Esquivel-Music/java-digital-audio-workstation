package com.benesquivelmusic.daw.core.concurrent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link ChangeNotifier} — the shared register / unregister /
 * allocation-free notify mechanism extracted from {@code Track} /
 * {@code MixerChannel} / {@code Transport} / {@code DawProject} (story 292 code
 * review). Pins the contract those models delegate to: who is notified, that
 * removal tokens work, and — the discriminating case — that a listener removed
 * mid-notification cannot corrupt the iteration (the fresh-snapshot semantics).
 */
class ChangeNotifierTest {

    @Test
    void firesToEveryRegisteredListener() {
        ChangeNotifier<String> notifier = new ChangeNotifier<>();
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        notifier.add(a::add);
        notifier.add(b::add);

        notifier.fire("X");

        assertThat(a).containsExactly("X");
        assertThat(b).containsExactly("X");
    }

    @Test
    void fireWithNoListenersIsANoOp() {
        ChangeNotifier<String> notifier = new ChangeNotifier<>();
        // No throw, nothing to observe.
        notifier.fire("X");
    }

    @Test
    void removalTokenUnregistersTheListener() {
        ChangeNotifier<String> notifier = new ChangeNotifier<>();
        List<String> received = new ArrayList<>();
        Runnable token = notifier.add(received::add);

        notifier.fire("one");
        token.run();
        notifier.fire("two"); // after unregister — not recorded

        assertThat(received).containsExactly("one");
    }

    @Test
    void removeUnregistersTheListener() {
        ChangeNotifier<String> notifier = new ChangeNotifier<>();
        List<String> received = new ArrayList<>();
        Consumer<String> listener = received::add;
        notifier.add(listener);

        notifier.fire("one");
        notifier.remove(listener);
        notifier.fire("two"); // after unregister — not recorded

        assertThat(received).containsExactly("one");
    }

    @Test
    void removingANeverAddedListenerIsSilent() {
        ChangeNotifier<String> notifier = new ChangeNotifier<>();
        // Must not throw.
        notifier.remove(s -> { });
    }

    @Test
    void rejectsNullListeners() {
        ChangeNotifier<String> notifier = new ChangeNotifier<>();
        assertThatNullPointerException()
                .isThrownBy(() -> notifier.add(null))
                .withMessage("listener must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> notifier.remove(null))
                .withMessage("listener must not be null");
    }

    @Test
    void aListenerRemovingAnotherDuringFireKeepsSnapshotSemantics() {
        ChangeNotifier<String> notifier = new ChangeNotifier<>();
        List<String> received = new ArrayList<>();

        AtomicReference<Consumer<String>> bRef = new AtomicReference<>();
        // Listener A, during its own notification, unregisters listener B.
        Consumer<String> listenerA = s -> notifier.remove(bRef.get());
        Consumer<String> listenerB = received::add;
        bRef.set(listenerB);
        notifier.add(listenerA);
        notifier.add(listenerB);

        // The snapshot is read once into a stable local before iterating, so B
        // still receives THIS notification even though A removed it mid-loop —
        // and the iteration does not throw.
        notifier.fire("X");
        assertThat(received).containsExactly("X");

        // B was removed for good; the NEXT notification skips it.
        received.clear();
        notifier.fire("Y");
        assertThat(received).isEmpty();
    }
}
