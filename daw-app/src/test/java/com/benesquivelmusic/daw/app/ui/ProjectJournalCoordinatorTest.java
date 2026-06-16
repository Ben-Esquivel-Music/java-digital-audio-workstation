package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.core.session.WorkingSession;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;

import javafx.application.Platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 298 — focused tests for {@link ProjectJournalCoordinator}: the
 * preference gate, the open/close lifecycle, journal-segment sealing into the
 * active {@link WorkingSession}, and the {@link Backpressure}→UI mapping. Uses a
 * real {@link DefaultEventBus} + {@link CheckpointManager} + {@link FxDispatcher}
 * + {@link ProjectOperationProgress}, never {@code MainController}.
 *
 * <p>No {@code @ExtendWith(JavaFxToolkitExtension.class)} — the coordinator and
 * the {@link ProjectOperationProgress} model touch no JavaFX node, and a plain
 * {@link FxDispatcher} runs work via {@code Platform.runLater}; the model
 * mutations under test are read back through {@link ProjectOperationProgress}'s
 * getters without needing a realized scene. The few model reads that go through
 * the dispatcher are pumped via {@link FxDispatcher#pulse()} which the test
 * drives directly. The {@link NotificationBar} is a JavaFX node, so it is built
 * once on the FX thread (the toolkit extension); the tests never trigger the
 * UNRESPONSIVE notification path that would touch it.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectJournalCoordinatorTest {

    @TempDir
    Path projectDir;

    private FxDispatcher dispatcher;
    private ProjectOperationProgress progress;
    private CheckpointManager checkpointManager;
    private SessionManager sessionManager;
    private EventBus bus;
    private NotificationBar notifications;
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws Exception {
        dispatcher = new FxDispatcher();
        progress = new ProjectOperationProgress(dispatcher);
        checkpointManager = new CheckpointManager(AutoSaveConfig.DEFAULT);
        sessionManager = new SessionManager();
        // ON_CALLER_THREAD delivery is the journal recorder's mode; no UI executor needed.
        bus = DefaultEventBus.builder().build();
        AtomicReference<NotificationBar> barRef = new AtomicReference<>();
        runOnFxThread(() -> barRef.set(new NotificationBar()));
        notifications = barRef.get();
    }

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.close();
        }
    }

    private ProjectJournalCoordinator newCoordinator() {
        return new ProjectJournalCoordinator(
                progress,
                notifications,
                dispatcher,
                enabled::get,
                () -> bus,
                checkpointManager,
                sessionManager,
                InstantSource.fixed(Instant.parse("2026-03-21T09:00:00Z")));
    }

    @Test
    void disabledPreferenceMakesOpenANoOp() {
        enabled.set(false);
        ProjectJournalCoordinator coordinator = newCoordinator();

        coordinator.openFor(projectDir);

        assertThat(coordinator.isOpen()).isFalse();
        // No journal/ directory is created when disabled.
        assertThat(Files.exists(projectDir.resolve("journal"))).isFalse();
        coordinator.closeCurrent(null); // idempotent no-op
    }

    @Test
    void enabledOpenCreatesJournalAndCloseSealsSegmentsIntoSession() throws Exception {
        enabled.set(true);
        // An active session for the coordinator to seal on close.
        WorkingSession active = WorkingSession.start(
                Instant.parse("2026-03-21T09:00:00Z"), ZoneId.of("UTC"));
        AtomicReference<WorkingSession> sealedRef = new AtomicReference<>();
        ProjectJournalCoordinator coordinator = newCoordinator();
        coordinator.setSessionSealedSink((dir, sealed) -> sealedRef.set(sealed));

        coordinator.openFor(projectDir);
        assertThat(coordinator.isOpen()).isTrue();
        // start() creates journal/ and the first segment.
        assertThat(Files.isDirectory(projectDir.resolve("journal"))).isTrue();

        // Publish a domain event — the recorder offers it to the writer.
        bus.publish(new MixerEvent.MuteChanged(UUID.randomUUID(), true, Instant.now()));

        // Close: drains, seals the supplied session with the segment name(s),
        // persists. The session to seal is captured by the caller (here, the
        // test) and passed in — the coordinator no longer reads it live.
        coordinator.closeCurrent(active);
        assertThat(coordinator.isOpen()).isFalse();

        // The session was sealed with at least segment-000.bin folded in, and the
        // sink received it.
        WorkingSession sealed = sealedRef.get();
        assertThat(sealed).isNotNull();
        assertThat(sealed.journalSegments()).contains("segment-000.bin");
        assertThat(sealed.isActive()).isFalse();

        // And the manifest was written to disk (history reflects the sealed session).
        List<WorkingSession> history = sessionManager.history(projectDir);
        assertThat(history).isNotEmpty();
        assertThat(history.getFirst().journalSegments()).contains("segment-000.bin");
    }

    @Test
    void reopenForSealsOutgoingSessionThenOpensTheNextProject(@TempDir Path projectB) throws Exception {
        enabled.set(true);
        WorkingSession sessionA = WorkingSession.start(
                Instant.parse("2026-03-21T09:00:00Z"), ZoneId.of("UTC"));
        AtomicReference<WorkingSession> sealedRef = new AtomicReference<>();
        ProjectJournalCoordinator coordinator = newCoordinator();
        coordinator.setSessionSealedSink((dir, sealed) -> sealedRef.set(sealed));

        coordinator.openFor(projectDir);                 // project A
        assertThat(coordinator.isOpen()).isTrue();
        bus.publish(new MixerEvent.MuteChanged(UUID.randomUUID(), true, Instant.now()));

        // Switch to project B in one serialized step: seal A's session, open B.
        coordinator.reopenFor(projectB, sessionA);

        assertThat(coordinator.isOpen())
                .as("project B's journal is open after the reopen")
                .isTrue();
        assertThat(Files.isDirectory(projectB.resolve("journal"))).isTrue();
        // Project A's session was sealed with its segment folded in.
        WorkingSession sealed = sealedRef.get();
        assertThat(sealed).isNotNull();
        assertThat(sealed.journalSegments()).contains("segment-000.bin");
        assertThat(sessionManager.history(projectDir)).isNotEmpty();

        coordinator.closeCurrent(null);                  // close B
    }

    @Test
    void openSeedsJournalCellToCleanStateOnPulse() {
        enabled.set(true);
        ProjectJournalCoordinator coordinator = newCoordinator();

        coordinator.openFor(projectDir);
        // The seed mutations are keyed-coalesced; pump the dispatcher to apply them.
        dispatcher.pulse();

        assertThat(progress.getJournalEventsQueued()).isZero();
        assertThat(progress.getJournalBackpressure())
                .isEqualTo(ProjectOperationProgress.JournalBackpressure.NORMAL);

        coordinator.closeCurrent(null);
    }

    private static void runOnFxThread(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for JavaFX action to complete");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }
}
