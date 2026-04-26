package com.benesquivelmusic.daw.sdk.store;

import com.benesquivelmusic.daw.sdk.event.ProjectChange;
import com.benesquivelmusic.daw.sdk.model.Project;
import com.benesquivelmusic.daw.sdk.model.Track;
import com.benesquivelmusic.daw.sdk.model.TrackType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;

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
    void apply_withNoChange_emitsNothing() throws Exception {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            java.util.concurrent.CountDownLatch anyEvent = new java.util.concurrent.CountDownLatch(1);
            store.changes().subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(ProjectChange item) { anyEvent.countDown(); }
                @Override public void onError(Throwable throwable) { }
                @Override public void onComplete() { }
            });
            store.apply(CompoundAction.identity());
            // Bounded wait — must not be tripped at all.
            assertThat(anyEvent.await(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("no-op apply must not emit any event")
                    .isFalse();
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

    @Test
    void compoundAction_andThen_rejectsNullAfter() {
        CompoundAction a = CompoundAction.identity();
        assertThatThrownBy(() -> a.andThen(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("after");
    }

    @Test
    void applyForTransition_returnsExactSnapshotPair() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            Track t = Track.of("X", TrackType.AUDIO);
            ProjectStore.Transition tr = store.applyForTransition(p -> p.putTrack(t));
            assertThat(tr.before().tracks()).isEmpty();
            assertThat(tr.after().tracks()).containsKey(t.id());
            assertThat(store.project()).isEqualTo(tr.after());
        }
    }

    @Test
    void concurrentWriters_eventOrderMatchesCommitOrder() throws Exception {
        // With the write-lock in place, the per-entity event sequence must
        // describe the snapshots produced by each commit. We commit a
        // strictly increasing volume on the same track from many threads
        // and assert that the published TrackUpdated events form a chain
        // where each event's previous equals the prior event's next.
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            List<ProjectChange> received = subscribe(store);

            Track t = Track.of("X", TrackType.AUDIO);
            store.apply(p -> p.putTrack(t));
            waitFor(() -> received.size() == 1);

            int writers = 8;
            int perWriter = 25;
            Thread[] threads = new Thread[writers];
            java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
            for (int w = 0; w < writers; w++) {
                threads[w] = new Thread(() -> {
                    for (int i = 0; i < perWriter; i++) {
                        int s = seq.incrementAndGet();
                        double vol = s / 1000.0;
                        store.apply(p -> {
                            Track cur = p.tracks().get(t.id());
                            return p.putTrack(cur.withVolume(vol));
                        });
                    }
                });
            }
            for (Thread th : threads) th.start();
            for (Thread th : threads) th.join();

            int total = 1 + writers * perWriter;
            waitFor(() -> received.size() == total);

            // Walk the TrackUpdated chain and verify continuity.
            Track lastNext = ((ProjectChange.TrackAdded) received.get(0)).next();
            for (int i = 1; i < received.size(); i++) {
                ProjectChange.TrackUpdated u = (ProjectChange.TrackUpdated) received.get(i);
                assertThat(u.previous())
                        .as("event %d previous must equal preceding next", i)
                        .isEqualTo(lastNext);
                lastNext = u.next();
            }
            assertThat(store.project().tracks().get(t.id())).isEqualTo(lastNext);
        }
    }

    @Test
    void diffEventsAreDeterministicallyOrderedByUuid() {
        try (ProjectStore store = new ProjectStore(Project.empty("s"))) {
            List<ProjectChange> received = subscribe(store);

            // Force a fixed UUID ordering so we can assert the sequence.
            java.util.UUID idA = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
            java.util.UUID idB = java.util.UUID.fromString("00000000-0000-0000-0000-000000000002");
            java.util.UUID idC = java.util.UUID.fromString("00000000-0000-0000-0000-000000000003");
            Track a = Track.of("A", TrackType.AUDIO).withId(idA);
            Track b = Track.of("B", TrackType.AUDIO).withId(idB);
            Track c = Track.of("C", TrackType.AUDIO).withId(idC);

            // Insert in reverse order; emitted events must still be A, B, C.
            store.apply(p -> p.putTrack(c).putTrack(b).putTrack(a));
            waitFor(() -> received.size() == 3);

            assertThat(received.get(0)).isInstanceOfSatisfying(ProjectChange.TrackAdded.class,
                    e -> assertThat(e.id()).isEqualTo(idA));
            assertThat(received.get(1)).isInstanceOfSatisfying(ProjectChange.TrackAdded.class,
                    e -> assertThat(e.id()).isEqualTo(idB));
            assertThat(received.get(2)).isInstanceOfSatisfying(ProjectChange.TrackAdded.class,
                    e -> assertThat(e.id()).isEqualTo(idC));
        }
    }

    @Test
    void changeEventIdAlwaysMatchesCarriedEntity() {
        Track t = Track.of("T", TrackType.AUDIO);
        ProjectChange.TrackAdded added = new ProjectChange.TrackAdded(t);
        ProjectChange.TrackUpdated updated = new ProjectChange.TrackUpdated(t, t.withVolume(0.5));
        ProjectChange.TrackRemoved removed = new ProjectChange.TrackRemoved(t);
        assertThat(added.id()).isEqualTo(t.id());
        assertThat(updated.id()).isEqualTo(t.id());
        assertThat(removed.id()).isEqualTo(t.id());

        // Mismatched ids on Updated must be rejected.
        Track other = Track.of("Other", TrackType.AUDIO);
        assertThatThrownBy(() -> new ProjectChange.TrackUpdated(t, other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");
    }

    private static List<ProjectChange> subscribe(ProjectStore store) {
        List<ProjectChange> received = new CopyOnWriteArrayList<>();
        store.changes().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ProjectChange item) { received.add(item); }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
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
