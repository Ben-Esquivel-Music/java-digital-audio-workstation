package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A take recorded after a project is reopened, before anything is saved, is
 * attributed to the track by the id the last saved file gives it (story 330,
 * studio-engineer ruling R1): the take folder's {@code <trackId>} segment and
 * the manifest's {@code track=} entry equal that file's {@code track@id}, so
 * crash rescue and relink can match every take of a track across sessions to
 * the track on screen.
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class TakeRecordedAfterAReopenCarriesTheSavedTrackIdTest {

    @TempDir
    Path tempDir;

    @Test
    void theTakeFolderAndTheManifestNameTheTrackByItsSavedId() throws Exception {
        ProjectManager manager = new ProjectManager(
                new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
        Path projectDir = manager.createProject("Session", tempDir).projectPath();
        DawProject saved = new DawProject("Session", MONO_16);
        Track vox = saved.createAudioTrack("Vox");
        vox.setArmed(true);
        vox.setInputRouting(new InputRouting(0, 1));
        saved(manager, saved);
        manager.closeProject().join();
        Path projectFile = projectDir.resolve("project.daw");
        byte[] savedBytes = Files.readAllBytes(projectFile);
        String savedId = savedTrackId(projectFile);

        manager.openProject(projectDir);
        DawProject reopened = manager.getCurrentDawProject();
        Track track = reopened.getTracks().getFirst();
        Transport transport = reopened.getTransport();
        AudioEngine engine = new AudioEngine(MONO_16);
        Path take = TakeDirectories.allocate(ProjectManager.audioDirectory(projectDir), Instant.now());
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, take, List.of(track));
        try {
            startRecording(pipeline);
            feedRamp(engine, transport, pipeline, 0, 4, 4);
            stopRecording(pipeline);
        } finally {
            manager.closeProject().join();
        }

        assertThat(Files.readAllBytes(projectFile)).as("fixture: nothing was saved after the reopen")
                .isEqualTo(savedBytes);
        assertThat(take.resolve(savedId).resolve("segment-000.wav"))
                .as("the take folder's <trackId> segment is the saved track@id").isRegularFile();
        Path manifestFile = take.resolve(TakeManifest.FILE_NAME);
        assertThat(TakeManifest.read(manifestFile).tracks()).extracting(TakeManifest.TrackEntry::trackId)
                .as("the manifest's track= entry").containsExactly(savedId);
        assertThat(Files.readString(manifestFile)).contains("track=" + savedId + "|");
    }

    /** The {@code track@id} of the only track in the saved file, read from the file itself. */
    private static String savedTrackId(Path projectFile) throws Exception {
        Element track = (Element) DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(projectFile.toFile()).getElementsByTagName("track").item(0);
        return track.getAttribute("id");
    }
}
