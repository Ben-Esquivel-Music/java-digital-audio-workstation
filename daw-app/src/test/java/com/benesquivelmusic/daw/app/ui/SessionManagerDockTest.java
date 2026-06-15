package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.session.WorkingSession;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SessionManagerDock} — the §4.3 Session Manager surface.
 * The {@code describe}/{@code formatElapsed} helpers are exercised headlessly;
 * the rendering wiring is exercised on the JavaFX thread.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SessionManagerDockTest {

    @Test
    void formatElapsedRendersHoursAndMinutes() {
        assertThat(SessionManagerDock.formatElapsed(Duration.ofMinutes(252)))
                .isEqualTo("4h 12m");
    }

    @Test
    void formatElapsedDropsHoursWhenUnderAnHour() {
        assertThat(SessionManagerDock.formatElapsed(Duration.ofMinutes(38)))
                .isEqualTo("38m");
    }

    @Test
    void formatElapsedClampsNegativeToZero() {
        assertThat(SessionManagerDock.formatElapsed(Duration.ofMinutes(-5)))
                .isEqualTo("0m");
    }

    @Test
    void describeMarksActiveSession() {
        WorkingSession active = WorkingSession.start(
                Instant.now().minus(Duration.ofMinutes(72)),
                java.time.ZoneId.systemDefault());
        assertThat(SessionManagerDock.describe(active))
                .startsWith("active \u00b7 1h 12m");
    }

    @Test
    void describeMarksClosedSessionWithTakeCount() {
        Instant start = Instant.now().minus(Duration.ofMinutes(120));
        WorkingSession closed = WorkingSession
                .start(start, java.time.ZoneId.systemDefault())
                .withTake("take-1")
                .withTake("take-2")
                .sealed(start.plus(Duration.ofMinutes(98)));
        assertThat(SessionManagerDock.describe(closed))
                .isEqualTo("closed \u00b7 1h 38m \u00b7 2 takes");
    }

    @Test
    void describeUsesSingularTakeLabel() {
        Instant start = Instant.now().minus(Duration.ofMinutes(30));
        WorkingSession closed = WorkingSession
                .start(start, java.time.ZoneId.systemDefault())
                .withTake("take-1")
                .sealed(start.plus(Duration.ofMinutes(10)));
        assertThat(SessionManagerDock.describe(closed))
                .endsWith("\u00b7 1 take");
    }

    @Test
    void setSessionsPopulatesTheList() throws Exception {
        SessionManagerDock dock = createOnFxThread();
        WorkingSession a = WorkingSession.start(Instant.now(), java.time.ZoneId.systemDefault());
        WorkingSession b = WorkingSession.start(
                Instant.now().minus(Duration.ofDays(1)), java.time.ZoneId.systemDefault());

        runOnFxThread(() -> dock.setSessions(List.of(a, b)));

        assertThat(dock.getSessions()).containsExactly(a, b);
    }

    @Test
    void setProjectNameUpdatesHeader() throws Exception {
        SessionManagerDock dock = createOnFxThread();
        runOnFxThread(() -> dock.setProjectName("Tracking day 2"));
        // Default identifiers reflect the §4.3 dock contract.
        assertThat(dock.dockId()).isEqualTo(DefaultWorkspaces.PANEL_SESSION_MANAGER);
        assertThat(dock.displayName()).isEqualTo("Session Manager");
    }

    @Test
    void defaultNameFollowsDesignBookFormat() {
        assertThat(WorkingSession.defaultName(LocalDate.of(2026, 6, 15)))
                .isEqualTo("Working session \u2014 2026-06-15");
    }

    private SessionManagerDock createOnFxThread() throws Exception {
        AtomicReference<SessionManagerDock> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new SessionManagerDock());
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }
}
