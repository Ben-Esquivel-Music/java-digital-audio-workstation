package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryChoice;
import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryDialog;
import com.benesquivelmusic.daw.core.persistence.journal.RecoverySummary;

import javafx.application.Platform;
import javafx.scene.control.ButtonType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 298 — verifies the {@link RecoveryDialog} (Project Manager Design Book
 * §4.6.1) renders the three recovery options + Cancel and formats the recovery
 * figures from a {@link RecoverySummary}. Built on the FX thread (the dialog's
 * {@code DawgDialog} chrome touches the toolkit) but never {@code showAndWait()}
 * — the assertions go through the dialog's test accessors.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RecoveryDialogOptionsTest {

    private static final Instant CHECKPOINT_TIME = Instant.parse("2026-03-21T14:05:00Z");
    private static final Instant LAST_EVENT_TIME = Instant.parse("2026-03-21T14:48:30Z");

    private static RecoverySummary sampleSummary() {
        return new RecoverySummary(
                7,                                  // checkpointIndex
                CHECKPOINT_TIME,                    // checkpointTime
                "checkpoint-007-20260321T140500.daw",
                "MixerEvent.MuteChanged",           // lastEventType
                LAST_EVENT_TIME,                    // lastEventTime
                120L,                               // replayableEventCount
                Duration.ofMinutes(43).plusSeconds(30), // non-zero replayableSpan
                List.of("segment-000.bin", "segment-001.bin"),
                4096L,                              // journalBytes
                List.of("MixerEvent.MuteChanged", "TrackEvent.VolumeChanged"));
    }

    @Test
    void rendersThreeOptionsCancelAndFormattedFigures() throws Exception {
        RecoverySummary summary = sampleSummary();
        AtomicReference<RecoveryDialog> dialogRef = new AtomicReference<>();
        runOnFxThread(() -> dialogRef.set(new RecoveryDialog(summary)));
        RecoveryDialog dialog = dialogRef.get();
        assertThat(dialog).isNotNull();

        // (a) All THREE option buttons + Cancel are present on the dialog pane.
        List<ButtonType> buttonTypes = dialog.getDialogPane().getButtonTypes();
        assertThat(buttonTypes)
                .as("three recovery options + Cancel")
                .contains(dialog.recoverEverythingButtonType(),
                        dialog.restoreCheckpointButtonType(),
                        dialog.inspectEventsButtonType(),
                        dialog.cancelButtonType());

        // The result converter maps each option button to its RecoveryChoice.
        assertThat(dialog.getResultConverter().call(dialog.recoverEverythingButtonType()))
                .isEqualTo(RecoveryChoice.REPLAY_ALL);
        assertThat(dialog.getResultConverter().call(dialog.restoreCheckpointButtonType()))
                .isEqualTo(RecoveryChoice.CHECKPOINT_ONLY);
        assertThat(dialog.getResultConverter().call(dialog.inspectEventsButtonType()))
                .isEqualTo(RecoveryChoice.INSPECT_EVENTS);
        assertThat(dialog.getResultConverter().call(dialog.cancelButtonType()))
                .isEqualTo(RecoveryChoice.CANCEL);

        // (b) last-save text contains the checkpoint index "7" + a formatted time.
        assertThat(dialog.lastSaveText())
                .as("checkpoint index in the last-save figure")
                .contains("7");
        assertThat(dialog.lastSaveText())
                .as("checkpoint time is formatted (date present)")
                .contains("2026-03-21");

        // (c) last-event text contains the leaf event name.
        assertThat(dialog.lastEventText()).contains("MuteChanged");

        // (d) the replayable figure reflects the count (120) and the minutes (44,
        //     43m30s rounded up).
        assertThat(dialog.replayableText()).contains("120");
        assertThat(dialog.replayableText()).contains("44 min");
    }

    @Test
    void handlesNoCheckpointAndEmptyJournalGracefully() throws Exception {
        RecoverySummary none = new RecoverySummary(
                -1, null, "", "", null, 0L, Duration.ZERO, List.of(), 0L, List.of());
        AtomicReference<RecoveryDialog> dialogRef = new AtomicReference<>();
        runOnFxThread(() -> dialogRef.set(new RecoveryDialog(none)));
        RecoveryDialog dialog = dialogRef.get();
        assertThat(dialog).isNotNull();

        // No checkpoint → a clear "no checkpoint" figure, not "Checkpoint #-1".
        assertThat(dialog.lastSaveText()).doesNotContain("-1");
        assertThat(dialog.lastSaveText().toLowerCase()).contains("no checkpoint");
        // Empty journal → "no events" figure rather than a stray "·".
        assertThat(dialog.lastEventText().toLowerCase()).contains("no events");
        // Zero replayable span → just the count, no "min" suffix.
        assertThat(dialog.replayableText()).contains("0 events").doesNotContain("min");
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
