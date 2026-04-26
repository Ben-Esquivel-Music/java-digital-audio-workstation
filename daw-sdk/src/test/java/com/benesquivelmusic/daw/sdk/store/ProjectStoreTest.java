package com.benesquivelmusic.daw.sdk.store;

import com.benesquivelmusic.daw.sdk.event.ProjectChange;
import com.benesquivelmusic.daw.sdk.model.Project;
import com.benesquivelmusic.daw.sdk.model.Track;
import com.benesquivelmusic.daw.sdk.model.TrackType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectStoreTest {

    @Test
    void apply_atomicallyUpdatesAndPublishesPerEntityChanges() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            List<ProjectChange> received = subscribe(store);

            Track t = Track.of("Drums", TrackType.AUDIO);
            store.apply(p -> p.putTrack(t));

            waitFor(() -> !received.isEmpty());
            assertThat(received).hasSize(1);
            assertThat(received.get(0))
                    .isInstanceOfSatisfying(ProjectChange.TrackAdded.class, e ->
                            assertThat(e.next()).isEqualTo(t));

            // Update the same track — should yield a TrackUpdated event.
            store.apply(p -> p.putTrack(t.withVolume(0.5)));
            waitFor(() -> received.size() == 2);
            assertThat(received.get(1))
                    .isInstanceOfSatisfying(ProjectChange.TrackUpdated.class, e -> {
                        assertThat(e.previous().volume()).isEqualTo(1.0);
                        assertThat(e.next().volume()).isEqualTo(0.5);
                        assertThat(e.id()).isEqualTo(t.id());
                    });
        }
    }

    @Test
    void apply_withNoChange_emitsNothing() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            List<ProjectChange> received = subscribe(store);
            store.apply(CompoundAction.identity());
            // Give the publisher a beat — it must not deliver anything.
            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            assertThat(received).isEmpty();
        }
    }

    @Test
    void apply_rejectsNullResult() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            assertThatThrownBy(() -> store.apply(p -> null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void replace_emitsProjectReplacedAndPerEntityDiff() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            List<ProjectChange> received = subscribe(store);

            Track t = Track.of("X", TrackType.AUDIO);
            Project replacement = Project.empty("s").putTrack(t).withId(store.project().id());
            store.replace(replacement);

            waitFor(() -> received.size() >= 2);
            assertThat(received).first().isInstanceOf(ProjectChange.ProjectReplaced.class);
            assertThat(received).anyMatch(e -> e instanceof ProjectChange.TrackAdded);
        }
    }

    @Test
    void compoundAction_andThen_composesReducers() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            Track a = Track.of("A", TrackType.AUDIO);
            Track b = Track.of("B", TrackType.MIDI);

            CompoundAction action = ((CompoundAction) p -> p.putTrack(a))
                    .andThen(p -> p.putTrack(b));
            store.apply(action);

            assertThat(store.project().tracks()).hasSize(2);
        }
    }

    private static List<ProjectChange> subscribe(ProjectStore store) {
        List<ProjectChange> received = new CopyOnWriteArrayList<>();
        AtomicBoolean done = new AtomicBoolean();
        store.changes().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ProjectChange item) { received.add(item); }
            @Override public void onError(Throwable throwable) { done.set(true); }
            @Override public void onComplete() { done.set(true); }
        });
        return received;
    }

    private static void waitFor(java.util.function.BooleanSupplier cond) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for condition");
            }
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
}
