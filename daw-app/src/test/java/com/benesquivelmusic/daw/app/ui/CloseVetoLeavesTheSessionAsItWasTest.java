package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.LockStatus;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "Veto" (the studio engineer's rulings R1, R2,
 * R12 #4; design §1.5): with the guard's Cancel chosen, no FLUSH, SEAL or
 * RELEASE step has run and the session continues exactly as before the
 * close request — the journal still records, the lock is ours, the
 * checkpoints run, the project is dirty and the window live — except that a
 * recording the take rule stopped stays stopped. While the prompt asks,
 * every window that was showing is input-inert: a press on a floating
 * panel's control and on an editor stage's control is consumed by the
 * fence, and handled again once the close was cancelled.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CloseVetoLeavesTheSessionAsItWasTest {

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private Stage floatingPanel;
    private Stage insertEditor;
    private final AtomicInteger panelPresses = new AtomicInteger();
    private final AtomicInteger editorPresses = new AtomicInteger();
    private Button panelControl;
    private Button editorControl;

    @BeforeEach
    void songAWithAFloatingPanelAndAnEditorShowing() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.save.save();
        fixture.save.settle();
        runOnFx(() -> {
            panelControl = new Button("Fader");
            panelControl.addEventHandler(MouseEvent.MOUSE_PRESSED, _ -> panelPresses.incrementAndGet());
            floatingPanel = new Stage();
            floatingPanel.setTitle("Mixer (floating)");
            floatingPanel.setScene(new Scene(new StackPane(panelControl), 200, 120));
            floatingPanel.show();
            editorControl = new Button("Drive");
            editorControl.addEventHandler(MouseEvent.MOUSE_PRESSED, _ -> editorPresses.incrementAndGet());
            insertEditor = new Stage();
            insertEditor.setTitle("Insert editor");
            insertEditor.setScene(new Scene(new StackPane(editorControl), 200, 120));
            insertEditor.show();
        });
        fixture.edit("Bass");
    }

    @AfterEach
    void close() throws Exception {
        runOnFx(() -> {
            floatingPanel.hide();
            insertEditor.hide();
        });
        fixture.close();
    }

    @Test
    void cancelRunsNoStepAndTheWindowsAreInertOnlyWhileThePromptAsks() throws Exception {
        String lock = Files.readString(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME));
        fixture.save.promptAnswer = "Cancel";
        fixture.beforeTheUnsavedPrompt = () -> {
            press(panelControl);
            press(editorControl);
        };

        fixture.closeBox();
        fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

        assertThat(panelPresses).as("the fence consumed the floating panel's press while the prompt asked")
                .hasValue(0);
        assertThat(editorPresses).as("and the editor stage's").hasValue(0);
        assertThat(fixture.stepNames()).as("no FLUSH, SEAL or RELEASE ran")
                .containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        assertThat(onFx(() -> fixture.coordinator().isWindowInert())).isFalse();
        int promptsBefore = fixture.save.prompts.get();
        runOnFx(() -> fixture.lifecycle().whenProjectMayClose(ProjectLifecycleController.OPEN_PROJECT_DOOR, () -> { }));
        assertThat(fixture.save.prompts.get()).as("the close is no longer the switch pending: a door asks again")
                .isEqualTo(promptsBefore + 1);
        runOnFx(() -> {
            press(panelControl);
            press(editorControl);
        });
        assertThat(panelPresses).as("handled once the close was cancelled: the fence is lowered").hasValue(1);
        assertThat(editorPresses).hasValue(1);

        assertThat(onFx(() -> fixture.save.project.get().isDirty())).isTrue();
        assertThat(fixture.save.projectManager.getLockManager().status()).isEqualTo(LockStatus.HELD);
        assertThat(Files.readString(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)))
                .as("the lock is ours, as it was").isEqualTo(lock);
        assertThat(fixture.save.checkpoints.runningGeneration()).as("the checkpoints run").isPresent();
        assertThat(fixture.journal.isOpen()).as("the journal is not detached").isTrue();
        runOnFx(() -> fixture.bus.publish(new MixerEvent.GainChanged(UUID.randomUUID(), Instant.now())));
        fixture.journal.drainWithoutSeal(null);
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA)))
                .as("the journal still recorded after the veto").anyMatch(record -> !record.isControl())
                .noneMatch(JournalRecord::isSessionSeal);
    }

    @Test
    void aRecordingTheTakeRuleStoppedStaysStoppedAfterTheVeto() throws Exception {
        RecordingInFlightFixture recording = RecordingInFlightFixture.audio(fixture.save.project.get());
        try {
            fixture.transport = recording.controller();
            recording.start();
            fixture.stopTakeAnswer = "Stop the take and close";
            fixture.save.promptAnswer = "Cancel";

            fixture.closeBox();
            fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

            assertThat(fixture.stopTakePrompts).hasSize(1);
            assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
            assertThat(fixture.unsavedPrompts.getFirst().content())
                    .contains("Your changes include the take you just recorded.");
            assertThat(onFx(() -> recording.controller().isRecordingInFlight())).isFalse();
            assertThat(onFx(() -> fixture.save.project.get().getTransport().getState()))
                    .as("the recording the guard stopped stays stopped").isEqualTo(TransportState.STOPPED);
            assertThat(onFx(() -> recording.controller().isTakeBeingWritten())).isFalse();
        } finally {
            fixture.transport = null;
            recording.close();
        }
    }

    private static void press(Button control) {
        control.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, 1, 1, 1, 1, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null));
    }
}
