package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.JournalEnding.JournalEnd;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the studio engineer's critique Q4.4 ("if the seal had already begun, the second WARNING is shown"), as
 * the ruling on app fix pass 2a, S1, and the app-fix-2 review S4 ask it pinned: a keep after the journal's detach
 * while the journal end is already running — it is never interrupted, so its seal may still land — says only that
 * journaled persistence is paused, never that recovery evidence was preserved: once that seal lands, the next open
 * offers no recovery. The exit and Close Project alike; the project stays open. The journal end is held on a latch
 * once it has begun (the closer marks it begun before it runs the end), and released before the fixture is closed.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class KeepAfterTheDetachWhileTheSealRunsTest {

    @TempDir
    Path workspace;

    private final CountDownLatch sealBegan = new CountDownLatch(1);
    private final CountDownLatch releaseTheSeal = new CountDownLatch(1);
    private ExitFixture fixture;

    @BeforeEach
    void songAEditedWithItsSealHeldOnceBegun() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT, true, real -> new JournalEnding() {
            @Override
            public void detach() {
                real.detach();
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                Runnable end = real.prepare(mode);
                if (mode != JournalEnd.SEAL) {
                    return end;
                }
                return () -> {
                    sealBegan.countDown();
                    awaitQuietly(releaseTheSeal);
                    end.run();
                };
            }
        });
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Save and close";
    }

    @AfterEach
    void close() throws Exception {
        try {
            releaseTheSeal.countDown();
            fixture.awaitTheJournalEnded();
        } finally {
            fixture.close();
        }
    }

    @Test
    void anExitKeptOpenWhileItsSealRunsSaysOnlyThatJournalingIsPaused() throws Exception {
        fixture.closeBox();

        keepOnceTheSealBegan();

        assertThat(warnings()).containsExactly(
                "Exit cancelled. Journaled persistence is paused until this project is reopened.");
        assertThat(fixture.terminalExits).hasValue(0);
        assertThat(fixture.save.currentProjectIs(fixture.songA)).as("the project stays open").isTrue();
        assertThat(onFx(() -> fixture.save.project.get().getName())).isEqualTo("Song A");
    }

    @Test
    void aCloseProjectKeptOpenWhileItsSealRunsSaysOnlyThatJournalingIsPaused() throws Exception {
        fixture.closeProject();

        keepOnceTheSealBegan();

        assertThat(warnings()).containsExactly(
                "Close Project cancelled. Journaled persistence is paused until this project is reopened.");
        assertThat(fixture.save.currentProjectIs(fixture.songA)).as("the project stays open").isTrue();
        assertThat(onFx(() -> fixture.save.project.get().getName())).isEqualTo("Song A");
    }

    /** The seal begun and held; the surface shown; the ten seconds after the detach passed; then the keep. */
    private void keepOnceTheSealBegan() throws Exception {
        assertThat(sealBegan.await(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("the seal began").isTrue();
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.await(() -> fixture.surface() != null && fixture.surface().isOpen(), "the progress surface");
        fixture.fire(ExitCoordinator.KEEP_AFTER_DETACH_DELAY);
        fixture.await(() -> !fixture.surface().keepButton().isDisabled(), "the keep usable after the detach");
        runOnFx(() -> fixture.surface().keepButton().fire());
        fixture.await(() -> !fixture.coordinator().isWindowInert(), "the close ended");
    }

    private java.util.List<String> warnings() throws Exception {
        return fixture.save.notifications().stream().filter(entry -> entry.level() == NotificationLevel.WARNING)
                .map(NotificationEntry::message).toList();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(ExitFixture.GUARD.toMillis() * 3, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
