package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.RenderPipeline;
import com.benesquivelmusic.daw.core.audioimport.AudioFileImporter;
import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.SegmentFile;
import com.benesquivelmusic.daw.core.recording.SegmentWriter;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Save → close → reopen → rehydrate through {@link ProjectManager}, the
 * deserializer and the production {@link ClipRehydrator} (Persistence
 * Integrity book §5.1 round-trip contract, Audio row; §5.3 steps 4 and 5).
 */
class ClipRehydratorRoundTripTest {

    private static final int PROJECT_RATE = 48_000;
    private static final AudioFormat PROJECT_FORMAT = new AudioFormat(PROJECT_RATE, 2, 16, 512);
    private static final int BLOCK = 64;

    @TempDir
    Path tempDir;

    @Test
    void anImportedClipReopensWithTheSamplesItsImportHeldRendersAgainAndLeavesTheProjectClean()
            throws Exception {
        // A 44.1 kHz file imported into a 48 kHz project: the clip holds converted audio.
        Path source = sineWav(tempDir.resolve("library").resolve("tone.wav"), 44_100, 22_050);
        ProjectManager manager = newManager();
        Path projectDir = manager.createProject("Round Trip", tempDir).projectPath();
        DawProject project = new DawProject("Round Trip", PROJECT_FORMAT);
        AudioClip imported = new AudioFileImporter(project).importFile(source, 0.0).clip();
        float[][] beforeSave = imported.getAudioData();
        saved(manager, project);
        manager.abandonProject();

        ProjectManager reopened = newManager();
        reopened.openProject(projectDir);
        DawProject loaded = reopened.getCurrentDawProject();
        AudioClip clip = loaded.getTracks().getFirst().getClips().getFirst();
        assertThat(clip.getAudioData()).as("the load restores references, not samples").isNull();
        assertThat(reopened.getLastMissingAssets()).isEqualTo(MissingAssetsReport.EMPTY);
        int frames = beforeSave[0].length;
        assertThat(peak(render(loaded, frames))).as("the render path skips a clip with no audio").isZero();

        CollectingRehydrationListener listener = new CollectingRehydrationListener();
        RehydrationSession session = new ClipRehydrator()
                .start(loaded, reopened.getLastMissingAssets(), 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(result.attached()).containsExactly(clip);
        assertThat(result.rows()).isEmpty();
        assertThat(Arrays.deepEquals(clip.getAudioData(), beforeSave))
                .as("sample-identical to what the import held before the save")
                .isTrue();
        assertThat(clip.getSourceRateMetadata()).isNull();
        assertThat(peak(render(loaded, frames))).as("the clip renders again").isPositive();
        assertThat(loaded.isDirty()).as("loading is not an edit").isFalse();
        reopened.abandonProject();
    }

    @Test
    void aDeletedSourceHeldBySplitHalvesIsOneNotFoundAssetWithTwoHoldersAndIsNeverDecoded()
            throws Exception {
        Path source = sineWav(tempDir.resolve("library").resolve("gone.wav"), PROJECT_RATE, PROJECT_RATE);
        Path referenceFile = tempDir.resolve("library").resolve("reference-mix.wav");
        ProjectManager manager = newManager();
        Path projectDir = manager.createProject("Missing", tempDir).projectPath();
        DawProject project = new DawProject("Missing", PROJECT_FORMAT);
        AudioClip first = new AudioFileImporter(project).importFile(source, 0.0).clip();
        Track track = project.getTracks().getFirst();
        track.addClip(first.splitAt(first.getStartBeat() + first.getDurationBeats() / 2));
        project.getReferenceTrackManager().addReferenceTrack(new ReferenceTrack("Ref", referenceFile.toString()));
        saved(manager, project);
        manager.abandonProject();
        Files.delete(source);

        ProjectManager reopened = newManager();
        reopened.openProject(projectDir);
        DawProject loaded = reopened.getCurrentDawProject();
        MissingAssetsReport report = reopened.getLastMissingAssets();

        assertThat(report.assets()).singleElement().satisfies(row -> {
            assertThat(row.reference()).isEqualTo(new AssetReference.SingleFile(source.toString()));
            assertThat(row.reason()).isEqualTo(MissingAssetReason.NOT_FOUND);
            assertThat(row.holders()).extracting(MissingAsset.Holder::clipName)
                    .containsExactly("gone", "gone (split)");
            assertThat(row.holders()).extracting(MissingAsset.Holder::trackName)
                    .containsOnly("gone");
        });
        assertThat(report.assetCount()).isEqualTo(1);
        assertThat(report.silentClipCount()).isEqualTo(2);
        assertThat(report.missingReferenceTrackPaths()).as("a reference track is logged, not a row")
                .containsExactly(referenceFile.toString());

        RecordingSourceDecoder decoder = new RecordingSourceDecoder();
        CollectingRehydrationListener listener = new CollectingRehydrationListener();
        RehydrationSession session = new ClipRehydrator(decoder, 2, Long.MAX_VALUE)
                .start(loaded, report, 0.0, listener);
        listener.awaitFinished();

        assertThat(session.sourceCount()).isZero();
        assertThat(decoder.decodes()).as("a reported source is never attempted").isEmpty();
        assertThat(listener.outcomes()).isEmpty();
        assertThat(listener.finishes()).containsExactly(new RehydrationListener.Summary(0, 0, 0));
        for (AudioClip clip : loaded.getTracks().getFirst().getClips()) {
            assertThat(clip.getAudioData()).isNull();
        }
        reopened.abandonProject();
    }

    @Test
    void aRecordedTakeReopensAtItsCaptureRateWithTheCapturePathsMetadata() throws Exception {
        ProjectManager manager = newManager();
        Path projectDir = manager.createProject("Take", tempDir).projectPath();
        DawProject project = new DawProject("Take", PROJECT_FORMAT);
        Track track = project.createAudioTrack("Vocals");
        List<Path> segments = writeTake(projectDir, track, 2);
        AudioClip take = new AudioClip("Take 1", 0.0, 0.04, null);
        take.setSourceSegmentPaths(segments.stream().map(Path::toString).toList());
        track.addClip(take);
        saved(manager, project);
        manager.abandonProject();
        float[][] onDisk = SegmentFile.readFrames(segments);

        ProjectManager reopened = newManager();
        reopened.openProject(projectDir);
        DawProject loaded = reopened.getCurrentDawProject();
        AudioClip clip = loaded.getTracks().getFirst().getClips().getFirst();
        CollectingRehydrationListener listener = new CollectingRehydrationListener();
        RehydrationSession session = new ClipRehydrator()
                .start(loaded, reopened.getLastMissingAssets(), 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(result.attached()).containsExactly(clip);
        assertThat(result.rows()).isEmpty();
        assertThat(Arrays.deepEquals(clip.getAudioData(), onDisk))
                .as("both segments, in order, at the native 44.1 kHz — not converted to the project rate")
                .isTrue();
        assertThat(clip.getSourceRateMetadata())
                .isEqualTo(new SourceRateMetadata(44_100, 2, onDisk[0].length));
        assertThat(loaded.isDirty()).isFalse();
        reopened.abandonProject();
    }

    @Test
    void aTakeWithOneDeletedSegmentIsOneNotFoundAssetAndNothingOfItIsLoaded() throws Exception {
        ProjectManager manager = newManager();
        Path projectDir = manager.createProject("Partial", tempDir).projectPath();
        DawProject project = new DawProject("Partial", PROJECT_FORMAT);
        Track track = project.createAudioTrack("Vocals");
        List<Path> segments = writeTake(projectDir, track, 2);
        AudioClip take = new AudioClip("Take 1", 0.0, 0.04, null);
        take.setSourceSegmentPaths(segments.stream().map(Path::toString).toList());
        track.addClip(take);
        saved(manager, project);
        manager.abandonProject();
        Files.delete(segments.get(1));

        ProjectManager reopened = newManager();
        reopened.openProject(projectDir);
        DawProject loaded = reopened.getCurrentDawProject();
        MissingAssetsReport report = reopened.getLastMissingAssets();

        assertThat(report.assets()).singleElement().satisfies(row -> {
            assertThat(row.reference()).isInstanceOf(AssetReference.Take.class);
            assertThat(row.reference().references()).hasSize(2);
            assertThat(row.reason()).isEqualTo(MissingAssetReason.NOT_FOUND);
            assertThat(row.detail()).startsWith("1 of 2 segments").contains("segment-001.wav");
        });
        assertThat(report.assetCount()).isEqualTo(1);

        RecordingSourceDecoder decoder = new RecordingSourceDecoder();
        CollectingRehydrationListener listener = new CollectingRehydrationListener();
        new ClipRehydrator(decoder, 2, Long.MAX_VALUE).start(loaded, report, 0.0, listener);
        listener.awaitFinished();

        assertThat(decoder.decodes()).isEmpty();
        assertThat(loaded.getTracks().getFirst().getClips().getFirst().getAudioData()).isNull();
        reopened.abandonProject();
    }

    @Test
    void theMissingAssetsOfAnOpenAreResetByALegacyMetadataOnlyOpenAndByAbandon() throws Exception {
        ProjectManager manager = newManager();
        Path projectDir = manager.createProject("Has Missing", tempDir).projectPath();
        DawProject project = new DawProject("Has Missing", PROJECT_FORMAT);
        Track track = project.createAudioTrack("Bass");
        track.addClip(new AudioClip("ghost", 0.0, 1.0, tempDir.resolve("ghost.wav").toString()));
        saved(manager, project);
        manager.abandonProject();
        Path legacyDir = Files.createDirectories(tempDir.resolve("Legacy"));
        Files.writeString(legacyDir.resolve("project.daw"), """
                # DAW Project File
                name=Legacy
                created_at=2020-01-01T00:00:00Z
                last_modified=2020-01-01T00:00:00Z
                """);

        ProjectManager reopened = newManager();
        reopened.openProject(projectDir);
        assertThat(reopened.getLastMissingAssets().assetCount()).isEqualTo(1);
        reopened.abandonProject();
        assertThat(reopened.getLastMissingAssets()).isEqualTo(MissingAssetsReport.EMPTY);

        reopened.openProject(projectDir);
        assertThat(reopened.getLastMissingAssets().assetCount()).isEqualTo(1);
        // No close in between: the legacy branch itself must reset the report.
        reopened.openProject(legacyDir);
        assertThat(reopened.getCurrentDawProject()).isNull();
        assertThat(reopened.getLastMissingAssets())
                .as("the deserializer did not run: the previous open's list must not leak")
                .isEqualTo(MissingAssetsReport.EMPTY);
        reopened.abandonProject();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static ProjectManager newManager() {
        return new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    private static Path sineWav(Path file, int sampleRate, int frames) throws IOException {
        float[][] audio = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            float value = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
            audio[0][i] = value;
            audio[1][i] = value;
        }
        Files.createDirectories(file.getParent());
        WavExporter.write(audio, sampleRate, 16, DitherType.NONE, AudioMetadata.EMPTY, file);
        return file;
    }

    /** Writes a take of {@code count} sealed 44.1 kHz stereo segments under the project's audio folder. */
    private static List<Path> writeTake(Path projectDir, Track track, int count) throws IOException {
        Path takeDir = TakeDirectories.allocate(ProjectManager.audioDirectory(projectDir),
                Instant.parse("2026-10-20T10:00:00Z"));
        Path[] sealed = new Path[count];
        for (int s = 0; s < count; s++) {
            float[][] frames = new float[2][512];
            for (int i = 0; i < 512; i++) {
                frames[0][i] = (float) (0.1 * (s + 1) * Math.sin(i / 7.0));
                frames[1][i] = -frames[0][i];
            }
            String name = String.format("segment-%03d.wav.part", s);
            try (SegmentWriter writer = SegmentWriter.open(takeDir.resolve(track.getId()).resolve(name),
                    44_100.0, 2, 16, Duration.ZERO, System::nanoTime)) {
                writer.append(frames, 2, 512);
                sealed[s] = writer.seal();
            }
        }
        return List.of(sealed);
    }

    /** Renders the project's tracks offline from beat 0 through a plain mixer and master. */
    private static float[][] render(DawProject project, int frames) {
        Transport transport = new Transport();
        transport.setTempo(project.getTransport().getTempo());
        transport.play();
        Mixer mixer = new Mixer();
        for (Track track : project.getTracks()) {
            MixerChannel channel = new MixerChannel(track.getName());
            channel.setVolume(1.0);
            mixer.addChannel(channel);
        }
        mixer.prepareForPlayback(2, BLOCK);
        MasteringChain master = new MasteringChain(2);
        master.allocateIntermediateBuffers(2, BLOCK);
        RenderPipeline pipeline = new RenderPipeline(new AudioFormat(PROJECT_RATE, 2, 16, BLOCK), 64, BLOCK);
        float[][] out = new float[2][frames];
        pipeline.renderOffline(transport, mixer, project.getTracks(), null, master, out, frames, BLOCK);
        return out;
    }

    private static float peak(float[][] audio) {
        float peak = 0.0f;
        for (float[] channel : audio) {
            for (float sample : channel) {
                peak = Math.max(peak, Math.abs(sample));
            }
        }
        return peak;
    }
}
