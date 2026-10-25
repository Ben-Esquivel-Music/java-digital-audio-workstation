package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.CompileCompAction;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A save taken while a take lane is auditioned writes the selected comp, not
 * the soloed take (PR #984 review), and leaves the live session untouched.
 * The serializer is the one choke point every writer goes through — Save,
 * Save As, the autosave checkpoint, snapshots and the archive.
 */
class AuditionSaveRoundTripTest {

    @TempDir
    Path tmp;

    private final ProjectSerializer serializer = new ProjectSerializer();
    private final ProjectDeserializer deserializer = new ProjectDeserializer();

    private DawProject project;
    private Track vox;
    private TakeComping comping;
    private List<String> lane0Segments;
    private List<String> lane1Segments;
    private AudioClip userClip;
    private AudioClip bassClip;

    @BeforeEach
    void setUp() {
        project = new DawProject("Audition", AudioFormat.CD_QUALITY);
        vox = project.createAudioTrack("Vox");
        comping = vox.getTakeComping();
        lane0Segments = List.of(path("take-1/segment-000.wav"), path("take-1/segment-001.wav"));
        lane1Segments = List.of(path("take-2/segment-000.wav"));
        comping.addTakeLane(lane("Take 1", lane0Segments, 1.0));
        comping.addTakeLane(lane("Take 2", lane1Segments, 0.5));
        // A partial selection on lane 0 — so the comp differs from the soloed lane 1.
        comping.addCompRegion(new CompRegion(0, 2.0, 3.0));
        new CompileCompAction(vox).execute();
        // A user clip INSIDE the comp range, and another track's clip.
        userClip = new AudioClip("Overdub", 3.0, 1.0, path("overdub.wav"));
        userClip.setGainDb(-2.0);
        vox.addClip(userClip);
        bassClip = new AudioClip("Bass", 0.0, 8.0, path("bass.wav"));
        project.createAudioTrack("Bass").addClip(bassClip);
    }

    @Test
    void aSaveDuringAnAuditionRoundTripsTheSelectionAndLeavesTheSessionUntouched() throws IOException {
        audition(1);
        project.markClean();
        List<AudioClip> liveClips = List.copyOf(vox.getClips());

        DawProject restored = deserializer.deserialize(serializer.serialize(project));

        List<AudioClip> saved = restored.getTracks().get(0).getClips();
        assertThat(saved).hasSize(2);
        assertUserClip(saved.getFirst());
        assertLane0Slice(saved.get(1));
        assertThat(saved).noneMatch(clip -> clip.getSourceSegmentPaths().equals(lane1Segments)
                || lane1Segments.contains(clip.getSourceFilePath()));
        AudioClip restoredBass = restored.getTracks().get(1).getClips().getFirst();
        assertThat(restoredBass.getSourceFilePath()).isEqualTo(bassClip.getSourceFilePath());
        assertThat(restoredBass.getDurationBeats()).isEqualTo(8.0);

        // The live session is untouched: still auditioning lane 1, the same clip
        // instances, the pass not advanced, the dirty bit as it was. (It adds no
        // undo entry by construction: daw-core's writers have no access to the
        // undo history — the application owns the UndoManager, and neither
        // DawProject, Track, TakeComping, the serializer nor the archiver holds
        // or reaches one — so there is nothing here to assert about it.)
        assertThat(comping.getTakeLane(1).isSoloed()).isTrue();
        assertThat(vox.getClips()).containsExactlyElementsOf(liveClips);
        AudioClip auditionClip = liveClips.stream().filter(comping::isCompOutput).findFirst().orElseThrow();
        assertThat(comping.isSupersededOutput(auditionClip)).as("pass not advanced").isFalse();
        vox.removeClip(auditionClip);
        assertThat(vox.addClip(auditionClip)).as("an undo-style re-add of current output").isTrue();
        assertThat(project.isDirty()).isFalse();
    }

    @Test
    void endingTheAuditionAfterTheSavePlaysExactlyWhatWasSaved() throws IOException {
        audition(1);
        DawProject restored = deserializer.deserialize(serializer.serialize(project));

        comping.endAuditions();
        new CompileCompAction(vox).execute();

        List<AudioClip> live = vox.getClips();
        List<AudioClip> saved = restored.getTracks().get(0).getClips();
        assertThat(live).as("no range doubled").hasSize(2);
        assertThat(saved).hasSize(2);
        for (int i = 0; i < live.size(); i++) {
            assertSameSavedValues(saved.get(i), live.get(i));
        }
    }

    @Test
    void anEmptySelectionDuringAnAuditionSavesNoCompClips() throws IOException {
        audition(1);
        comping.clearCompRegions();

        DawProject restored = deserializer.deserialize(serializer.serialize(project));

        List<AudioClip> saved = restored.getTracks().get(0).getClips();
        assertThat(saved).hasSize(1);
        assertUserClip(saved.getFirst());
    }

    @Test
    void aNonAuditionedComptrackSerializesExactlyAsItsClips() throws IOException {
        DawProject restored = deserializer.deserialize(serializer.serialize(project));

        List<AudioClip> saved = restored.getTracks().get(0).getClips();
        assertThat(saved).hasSize(vox.getClips().size());
        for (int i = 0; i < saved.size(); i++) {
            assertSameSavedValues(saved.get(i), vox.getClips().get(i));
        }
    }

    @Test
    void aScheduledCheckpointDuringAnAuditionHoldsTheSelection() throws Exception {
        audition(1);
        Path projectDir = Files.createDirectories(tmp.resolve("Song"));
        CheckpointManager checkpoints = new CheckpointManager(
                new AutoSaveConfig(Duration.ofMillis(50), 10, true));
        // The capture source the application installs: the real serializer's
        // capture, taken on the model's owner thread and rendered on the
        // checkpoint writer.
        ProjectSerializer checkpointSerializer = new ProjectSerializer();
        CompletableFuture<CheckpointEvent> outcome = new CompletableFuture<>();
        checkpoints.addEventListener(event -> {
            if (event instanceof CheckpointEvent.Written || event instanceof CheckpointEvent.Failed) {
                outcome.complete(event);
            }
        });
        try (ExecutorService owner = Executors.newSingleThreadExecutor()) {
            checkpoints.setCaptureSource(owner, new CheckpointSource() {
                @Override
                public long revision() {
                    return project.getEditRevision();
                }

                @Override
                public boolean isClean() {
                    return !project.isDirty();
                }

                @Override
                public Renderer capture() throws IOException {
                    return checkpointSerializer.capture(project)::toXml;
                }
            });
            checkpoints.start(projectDir);
            try {
                assertThat(outcome.get(10, TimeUnit.SECONDS)).isInstanceOf(CheckpointEvent.Written.class);
            } finally {
                checkpoints.stop();
            }
        }
        Path written = ((CheckpointEvent.Written) outcome.join()).file();

        DawProject restored = deserializer.deserialize(Files.readString(written));

        List<AudioClip> saved = restored.getTracks().get(0).getClips();
        assertThat(saved).hasSize(2);
        assertUserClip(saved.getFirst());
        assertLane0Slice(saved.get(1));
        assertThat(comping.getTakeLane(1).isSoloed()).as("the audition keeps playing").isTrue();
    }

    private void assertLane0Slice(AudioClip slice) {
        assertThat(slice.getStartBeat()).isEqualTo(2.0);
        assertThat(slice.getDurationBeats()).isEqualTo(3.0);
        assertThat(slice.getSourceOffsetBeats()).as("lane offset 1.0 + 2 beats in").isEqualTo(3.0);
        assertThat(slice.getSourceSegmentPaths()).containsExactlyElementsOf(lane0Segments);
        assertThat(slice.getSourceFilePath()).isEqualTo(lane0Segments.getFirst());
    }

    private void assertUserClip(AudioClip clip) {
        assertThat(clip.getName()).isEqualTo("Overdub");
        assertThat(clip.getStartBeat()).isEqualTo(3.0);
        assertThat(clip.getDurationBeats()).isEqualTo(1.0);
        assertThat(clip.getGainDb()).isEqualTo(-2.0);
        assertThat(clip.getSourceFilePath()).isEqualTo(userClip.getSourceFilePath());
    }

    private static void assertSameSavedValues(AudioClip saved, AudioClip live) {
        assertThat(saved.getName()).isEqualTo(live.getName());
        assertThat(saved.getStartBeat()).isEqualTo(live.getStartBeat());
        assertThat(saved.getDurationBeats()).isEqualTo(live.getDurationBeats());
        assertThat(saved.getSourceOffsetBeats()).isEqualTo(live.getSourceOffsetBeats());
        assertThat(saved.getGainDb()).isEqualTo(live.getGainDb());
        assertThat(saved.getFadeInBeats()).isEqualTo(live.getFadeInBeats());
        assertThat(saved.getFadeOutBeats()).isEqualTo(live.getFadeOutBeats());
        assertThat(saved.getSourceFilePath()).isEqualTo(live.getSourceFilePath());
        assertThat(saved.getSourceSegmentPaths()).isEqualTo(live.getSourceSegmentPaths());
    }

    /** What the COMP tool's Alt-click does (CompToolHandler.altClickLane, daw-app). */
    private void audition(int laneIndex) {
        for (int i = 0; i < comping.getTakeLaneCount(); i++) {
            comping.getTakeLane(i).setSoloed(i == laneIndex);
        }
        new CompileCompAction(vox).execute();
    }

    private String path(String relative) {
        return tmp.resolve("media").resolve(relative).toAbsolutePath().toString();
    }

    private static TakeLane lane(String name, List<String> segments, double sourceOffsetBeats) {
        AudioClip take = new AudioClip(name, 0.0, 8.0, null);
        take.setSourceSegmentPaths(segments);
        take.setSourceOffsetBeats(sourceOffsetBeats);
        take.setAudioData(new float[][] { new float[16] });
        TakeLane lane = new TakeLane(name);
        lane.addClip(take);
        return lane;
    }
}
