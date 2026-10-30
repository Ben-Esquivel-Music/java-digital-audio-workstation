package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.project.DawProject;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Projects whose session can be recovered (story 331's Recover Session tests;
 * story 333's exit during a recovery): a saved project on disk with a
 * checkpoint of one track and a verified journal of changes after it, and
 * the answer to the recovery dialog its open shows.
 */
final class RecoverableProjects {

    /** The recovery dialog's title. */
    static final String RECOVERY_DIALOG_TITLE = "Recover unsaved work";

    private RecoverableProjects() {
    }

    /**
     * A saved project named {@code name} in {@code fixture}'s workspace, with
     * a checkpoint of one track, "Vocals" (the verified session-start base
     * holds the same document), and a journal of {@code changes} changes
     * after it.
     *
     * @return the project's folder
     */
    static Path withACheckpointAndAJournal(SaveFlowFixture fixture, String name, int changes) throws IOException {
        Path directory = fixture.anotherProjectOnDisk(name);
        DawProject checkpointed = new DawProject(name, AudioFormat.CD_QUALITY);
        checkpointed.createAudioTrack("Vocals");
        ProjectContext context = ProjectContext.forProject(directory);
        Files.createDirectories(context.checkpointDirectory());
        // Only this checkpoint: the save that made the project wrote one of its own.
        for (Path written : SaveFlowFixture.checkpointFiles(directory)) {
            Files.delete(written);
        }
        String checkpointXml = new ProjectSerializer().serialize(checkpointed);
        Files.writeString(context.checkpointDirectory().resolve("checkpoint-999-20260101T000000.daw"),
                checkpointXml, StandardCharsets.UTF_8);
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        Files.createDirectories(context.journalDirectory());
        Path base = Files.writeString(context.journalDirectory().resolve(JournalState.BASE_FILE), checkpointXml);
        byte[] baseHash = JournalState.hash(base).getBytes(StandardCharsets.UTF_8);
        new JournalRecord(0L, Instant.EPOCH, JournalRecord.SESSION_START_TYPE, null, baseHash)
                .writeTo(java.nio.channels.Channels.newChannel(frames));
        for (int sequence = 0; sequence < changes; sequence++) {
            ByteBuffer frame = JournalRecord.ofMutation(sequence + 1L, Instant.ofEpochMilli(1_000L + sequence),
                    "MixerEvent.MuteChanged", UUID.randomUUID(), null).toBuffer();
            byte[] bytes = new byte[frame.remaining()];
            frame.get(bytes);
            frames.writeBytes(bytes);
        }
        Files.write(context.journalDirectory().resolve("segment-000.bin"), frames.toByteArray());
        return directory;
    }

    /**
     * Answers every recovery dialog shown from now on with the button
     * labelled {@code label}, on a later FX turn, until the returned handle
     * is closed. Call on any thread.
     *
     * @return closes the answerer
     */
    static AutoCloseable answeringTheRecoveryDialogWith(String label) throws Exception {
        ListChangeListener<Window> answerer = change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && RECOVERY_DIALOG_TITLE.equals(stage.getTitle())) {
                        Platform.runLater(() -> answer(stage, label));
                    }
                }
            }
        };
        SaveFlowFixture.runOnFx(() -> Window.getWindows().addListener(answerer));
        return () -> SaveFlowFixture.runOnFx(() -> Window.getWindows().removeListener(answerer));
    }

    private static void answer(Stage stage, String label) {
        Node root = stage.getScene().getRoot();
        DialogPane pane = root instanceof DialogPane direct ? direct : (DialogPane) root.lookup(".dialog-pane");
        for (ButtonType type : pane.getButtonTypes()) {
            if (type.getText().equals(label) && pane.lookupButton(type) instanceof Button button) {
                button.fire();
                return;
            }
        }
        stage.hide();
    }
}
