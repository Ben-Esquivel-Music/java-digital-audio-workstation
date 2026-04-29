package com.benesquivelmusic.daw.sdk.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link LegacyListenerAdapter} bridges the supported
 * subset of new {@link DawEvent}s to legacy listener callbacks.
 *
 * <p>Currently only {@link ProjectEvent.Saved} is mapped (to
 * {@link AutoSaveListener#onAfterCheckpoint}). Other legacy callbacks
 * ({@code onBeforeCheckpoint}, {@code onCheckpointFailed}, and all
 * {@link RecordingListener} methods) are not bridged yet.</p>
 */
class LegacyListenerAdapterTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** Executor that runs tasks inline on the submitting thread. */
    private static final Executor DIRECT = Runnable::run;

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

        UUID projectId = UUID.randomUUID();
        try (var publisher = new SubmissionPublisher<DawEvent>(DIRECT, Flow.defaultBufferSize())) {
            publisher.subscribe(adapter);
            publisher.submit(new ProjectEvent.Saved(
                    projectId, java.nio.file.Path.of("/tmp/p.daw"), T0));
        }

        assertThat(listener.afterIds).hasSize(1);
        // Checkpoint id is unique per save: projectId + "-" + timestamp millis
        assertThat(listener.afterIds.getFirst())
                .isEqualTo(projectId + "-" + T0.toEpochMilli());
        assertThat(listener.beforeIds).isEmpty();
        assertThat(listener.failedIds).isEmpty();
    }

    @Test
    void nonProjectEventsDoNotDriveAutoSaveListener() {
        RecordingAutoSave listener = new RecordingAutoSave();
        LegacyListenerAdapter adapter = new LegacyListenerAdapter(null, listener);

        try (var publisher = new SubmissionPublisher<DawEvent>(DIRECT, Flow.defaultBufferSize())) {
            publisher.subscribe(adapter);
            publisher.submit(new TransportEvent.Started(0L, T0));
            publisher.submit(new TrackEvent.Added(UUID.randomUUID(), T0));
            publisher.submit(new ProjectEvent.Opened(
                    UUID.randomUUID(), java.nio.file.Path.of("/tmp/p.daw"), T0));
        }

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
        try (var publisher = new SubmissionPublisher<DawEvent>(DIRECT, Flow.defaultBufferSize())) {
            publisher.subscribe(adapter);
            publisher.submit(new ProjectEvent.Saved(
                    UUID.randomUUID(), java.nio.file.Path.of("/tmp/p.daw"), T0));
        }
        // Verifying no NPE was thrown by the adapter itself.
        assertThat(adapter.recordingListener()).isNull();
    }
}
