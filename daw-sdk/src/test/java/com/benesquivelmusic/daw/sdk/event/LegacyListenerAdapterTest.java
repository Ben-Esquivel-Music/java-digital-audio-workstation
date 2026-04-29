package com.benesquivelmusic.daw.sdk.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.SubmissionPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link LegacyListenerAdapter} bridges the new
 * {@link DawEvent} stream to legacy listener callbacks. Subscribing the
 * adapter to a {@link SubmissionPublisher} and emitting events should
 * drive the legacy listener exactly as the legacy engine did.
 */
class LegacyListenerAdapterTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static final class RecordingAutoSave implements AutoSaveListener {
        final List<String> beforeIds = new ArrayList<>();
        final List<String> afterIds = new ArrayList<>();
        final List<String> failedIds = new ArrayList<>();

        @Override public void onBeforeCheckpoint(String id) { beforeIds.add(id); }
        @Override public void onAfterCheckpoint(String id) { afterIds.add(id); }
        @Override public void onCheckpointFailed(String id, Throwable cause) { failedIds.add(id); }
    }

    @Test
    void projectSavedDrivesAutoSaveListener() {
        RecordingAutoSave listener = new RecordingAutoSave();
        LegacyListenerAdapter adapter = new LegacyListenerAdapter(null, listener);

        try (SubmissionPublisher<DawEvent> publisher = new SubmissionPublisher<>()) {
            publisher.subscribe(adapter);
            UUID projectId = UUID.randomUUID();
            publisher.submit(new ProjectEvent.Saved(
                    projectId, java.nio.file.Path.of("/tmp/p.daw"), T0));
            publisher.close();
        }

        // The publisher closed; wait briefly for delivery to complete.
        // SubmissionPublisher uses ForkJoinPool — onComplete is called
        // asynchronously, but onNext for already-submitted items must
        // run before onComplete. A short busy-wait keeps the test
        // deterministic without resorting to time-based sleeps.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (listener.afterIds.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(listener.afterIds).hasSize(1);
        assertThat(listener.beforeIds).isEmpty();
        assertThat(listener.failedIds).isEmpty();
    }

    @Test
    void nonProjectEventsDoNotDriveAutoSaveListener() {
        RecordingAutoSave listener = new RecordingAutoSave();
        LegacyListenerAdapter adapter = new LegacyListenerAdapter(null, listener);

        try (SubmissionPublisher<DawEvent> publisher = new SubmissionPublisher<>()) {
            publisher.subscribe(adapter);
            publisher.submit(new TransportEvent.Started(0L, T0));
            publisher.submit(new TrackEvent.Added(UUID.randomUUID(), T0));
            publisher.submit(new ProjectEvent.Opened(
                    UUID.randomUUID(), java.nio.file.Path.of("/tmp/p.daw"), T0));
            publisher.close();
        }

        // No after-checkpoint should be observed for non-Saved events.
        long deadline = System.nanoTime() + java.time.Duration.ofMillis(500).toNanos();
        while (System.nanoTime() < deadline) { Thread.onSpinWait(); }
        assertThat(listener.afterIds).isEmpty();
    }

    @Test
    void recordingListenerAccessorReturnsTheSameInstance() {
        RecordingListener rl = new RecordingListener() {
            @Override public void onRecordingStarted() { }
            @Override public void onRecordingPaused() { }
            @Override public void onRecordingResumed() { }
            @Override public void onRecordingStopped() { }
            @Override public void onNewSegmentCreated(int segmentIndex) { }
        };
        LegacyListenerAdapter adapter = new LegacyListenerAdapter(rl, null);
        assertThat(adapter.recordingListener()).isSameAs(rl);
    }

    @Test
    void nullListenersAreAllowed() {
        LegacyListenerAdapter adapter = new LegacyListenerAdapter(null, null);
        try (SubmissionPublisher<DawEvent> publisher = new SubmissionPublisher<>()) {
            publisher.subscribe(adapter);
            publisher.submit(new ProjectEvent.Saved(
                    UUID.randomUUID(), java.nio.file.Path.of("/tmp/p.daw"), T0));
        }
        // Just verifying no NPE was thrown by the adapter itself.
        assertThat(adapter.recordingListener()).isNull();
    }
}
