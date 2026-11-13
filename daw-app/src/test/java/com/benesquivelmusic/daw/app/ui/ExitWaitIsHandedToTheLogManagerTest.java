package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.logging.ExitHoldRegistration;
import com.benesquivelmusic.daw.app.logging.ExitHoldingLogManager;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production hand-off of the exit wait to the log manager (story 336,
 * Goals bullet 9): {@link MainController#registerExitWriteWait}
 * — the very method {@code initialize()} calls, which no test FXML-loads —
 * registers its {@code daw-exit-write-wait} hook with
 * {@link ExitHoldingLogManager}, holding the shutdown reset for the exit
 * wait's 30 s plus the 5 s margin. This is the one test through the real
 * default wiring of that seam; {@code SourcePinnedWiringTest} pins the line,
 * and the child-JVM {@code ExitWaitLinesReachTheLogTest} proves the hold on
 * the JDK's shutdown path.
 *
 * <p>The method registers a real JVM shutdown hook in this surefire JVM;
 * teardown removes it, so it never runs here, and puts the log manager's
 * registration back as the test found it, none included
 * ({@link ExitHoldRegistration}).</p>
 */
class ExitWaitIsHandedToTheLogManagerTest {

    private Thread registeredHook;
    private Optional<ExitHoldingLogManager.Hold> before;

    @BeforeEach
    void takeTheRegistration() {
        before = ExitHoldingLogManager.registeredHold();
    }

    @AfterEach
    void removeTheShutdownHookAndRestoreTheRegistration() {
        try {
            if (registeredHook != null) {
                Runtime.getRuntime().removeShutdownHook(registeredHook);
            }
        } finally {
            ExitHoldRegistration.restore(before);
        }
    }

    @Test
    void theRegisteredExitWaitIsTheHoldOfTheLogManagerForThirtyFiveSeconds() {
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));

        MainController.registerExitWriteWait(manager);

        Optional<ExitHoldingLogManager.Hold> hold = ExitHoldingLogManager.registeredHold();
        assertThat(hold).isPresent();
        registeredHook = hold.get().exitWait();
        assertThat(before).as("a new registration").isNotEqualTo(hold);
        assertThat(registeredHook.getName()).isEqualTo("daw-exit-write-wait");
        assertThat(registeredHook.getState()).as("a shutdown hook, not started").isEqualTo(Thread.State.NEW);
        assertThat(hold.get().hold()).isEqualTo(Duration.ofSeconds(35))
                .isEqualTo(ProjectManager.EXIT_WRITE_WAIT.plus(ExitHoldingLogManager.HOLD_MARGIN));
        assertThat(Runtime.getRuntime().removeShutdownHook(registeredHook))
                .as("the hold is the hook the JVM registered").isTrue();
        registeredHook = null;
    }
}
