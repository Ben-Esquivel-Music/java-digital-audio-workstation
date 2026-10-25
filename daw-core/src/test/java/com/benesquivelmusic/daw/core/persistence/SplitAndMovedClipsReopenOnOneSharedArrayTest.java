package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.RenderPipeline;
import com.benesquivelmusic.daw.core.audioimport.AudioFileImporter;
import com.benesquivelmusic.daw.core.audioimport.AudioImportResult;
import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An independent save, reopen and rehydrate contract probe with inputs unlike
 * the default fixtures: a 22,050 Hz stereo file whose channels differ (left a
 * rising ramp, right -0.37 times it) imported into a 96 kHz project, split
 * twice, the middle part moved to a second track, saved, and reopened with a
 * new {@link ProjectManager}; the production {@link ClipRehydrator} and file
 * decoder load it again.
 */
class SplitAndMovedClipsReopenOnOneSharedArrayTest {

    private static final int SOURCE_RATE = 22_050;
    private static final int PROJECT_RATE = 96_000;
    private static final AudioFormat PROJECT_FORMAT = new AudioFormat(PROJECT_RATE, 2, 24, 512);
    private static final int BLOCK = 128;
    private static final float RIGHT_GAIN = -0.37f;
    private static final long FINISH_GUARD_SECONDS = 30;

    @TempDir
    Path tempDir;

    private float[][] beforeSave;
    private ProjectManager reopened;
    private DawProject loaded;
    private List<AudioClip> loadedClips;
    private RehydrationSession.AttachResult attachResults;

    @BeforeEach
    void saveReopenAndRehydrate() throws Exception {
        Path source = asymmetricRampWav(tempDir.resolve("media").resolve("asymmetric ramp.wav"));
        ProjectManager manager = newManager();
        Path projectDir = manager.createProject("Probe Session", tempDir).projectPath();
        DawProject project = new DawProject("Probe Session", PROJECT_FORMAT);
        AudioImportResult imported = new AudioFileImporter(project).importFile(source, 0.0);
        Track first = imported.track();
        AudioClip head = imported.clip();
        beforeSave = head.getAudioData();
        assertThat(beforeSave).as("fixture: the import converted the file to the project rate").hasNumberOfRows(2);
        assertThat(beforeSave[0].length).as("fixture: one second at 96 kHz").isEqualTo(PROJECT_RATE);
        double end = head.getEndBeat();
        AudioClip middle = head.splitAt(end * 0.25);
        first.addClip(middle);
        AudioClip tail = middle.splitAt(end * 0.6);
        first.addClip(tail);
        Track second = project.createAudioTrack("Second");
        first.removeClip(middle);
        second.addClip(middle);
        assertThat(List.of(head.getAudioData(), middle.getAudioData(), tail.getAudioData()))
                .as("fixture: before the save the three parts share the import's array")
                .allSatisfy(audio -> assertThat(audio).isSameAs(beforeSave));
        saved(manager, project);
        manager.abandonProject();

        reopened = newManager();
        reopened.openProject(projectDir);
        loaded = reopened.getCurrentDawProject();
        loadedClips = new ArrayList<>();
        for (Track track : loaded.getTracks()) {
            loadedClips.addAll(track.getClips());
        }
        assertThat(loaded.getTracks()).as("fixture: both tracks reopened").hasSize(2);
        assertThat(loadedClips).as("fixture: three clips reopened").hasSize(3)
                .allSatisfy(clip -> assertThat(clip.getAudioData()).isNull());
        assertThat(loaded.isDirty()).as("fixture: a fresh open is clean").isFalse();

        Queue<RehydrationOutcome> outcomes = new ConcurrentLinkedQueue<>();
        CountDownLatch finished = new CountDownLatch(1);
        RehydrationSession session = new ClipRehydrator().start(loaded, reopened.getLastMissingAssets(), 0.0,
                new RehydrationListener() {
                    @Override
                    public void onOutcome(RehydrationOutcome outcome) {
                        outcomes.add(outcome);
                    }

                    @Override
                    public void onFinished(Summary summary) {
                        finished.countDown();
                    }
                });
        assertThat(session.sourceCount()).as("the three parts are one source").isEqualTo(1);
        assertThat(finished.await(FINISH_GUARD_SECONDS, TimeUnit.SECONDS)).as("the run finished").isTrue();
        List<AudioClip> attached = new ArrayList<>();
        List<MissingAsset> rows = new ArrayList<>();
        RehydrationOutcome outcome;
        while ((outcome = outcomes.poll()) != null) {
            RehydrationSession.AttachResult result = session.apply(outcome);
            attached.addAll(result.attached());
            rows.addAll(result.rows());
        }
        attachResults = new RehydrationSession.AttachResult(attached, rows);
    }

    @AfterEach
    void closeTheProject() {
        if (reopened != null) {
            reopened.abandonProject();
        }
    }

    @Test
    void theThreePartsOnTwoTracksHoldOneSharedArray() {
        assertThat(attachResults.attached()).containsExactlyInAnyOrderElementsOf(loadedClips);
        assertThat(attachResults.rows()).isEmpty();
        float[][] shared = loadedClips.getFirst().getAudioData();
        assertThat(shared).isNotNull();
        assertThat(loadedClips).allSatisfy(clip -> assertThat(clip.getAudioData())
                .as("%s holds the one decoded array", clip.getName()).isSameAs(shared));
    }

    @Test
    void theReloadedArrayIsSampleIdenticalToTheImportBeforeTheSave() {
        float[][] reloaded = loadedClips.getFirst().getAudioData();
        assertThat(reloaded).isNotNull();
        assertThat(Arrays.deepEquals(reloaded, beforeSave))
                .as("the 22,050 Hz file decoded to 96 kHz exactly as the import did")
                .isTrue();
        assertThat(reloaded[0]).isNotEqualTo(reloaded[1]);
    }

    @Test
    void loadingLeavesTheReopenedProjectClean() {
        assertThat(loaded.isDirty()).as("loading audio is not an edit").isFalse();
    }

    @Test
    void anOfflineRenderSoundsOnBothChannelsWithTheSourcesPolarity() {
        float[][] rendered = render(loaded, PROJECT_RATE);
        double left = 0.0;
        double right = 0.0;
        double product = 0.0;
        for (int i = 0; i < PROJECT_RATE; i++) {
            left += rendered[0][i];
            right += rendered[1][i];
            product += (double) rendered[0][i] * rendered[1][i];
        }
        assertThat(left).as("the left channel sounds, positive like its ramp").isPositive();
        assertThat(right).as("the right channel sounds, negative like its inverted ramp").isNegative();
        assertThat(product).as("the channels are in opposite polarity, not swapped or mirrored").isNegative();
        assertThat(-right / left).as("the right channel keeps its 0.37 ratio to the left")
                .isBetween(0.30, 0.44);
    }

    private static ProjectManager newManager() {
        return new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    private static Path asymmetricRampWav(Path file) throws IOException {
        float[][] audio = new float[2][SOURCE_RATE];
        for (int i = 0; i < SOURCE_RATE; i++) {
            float ramp = 0.9f * i / SOURCE_RATE;
            audio[0][i] = ramp;
            audio[1][i] = RIGHT_GAIN * ramp;
        }
        Files.createDirectories(file.getParent());
        WavExporter.write(audio, SOURCE_RATE, 16, DitherType.NONE, AudioMetadata.EMPTY, file);
        return file;
    }

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
        RenderPipeline pipeline = new RenderPipeline(new AudioFormat(PROJECT_RATE, 2, 24, BLOCK), 64, BLOCK);
        float[][] out = new float[2][frames];
        pipeline.renderOffline(transport, mixer, project.getTracks(), null, master, out, frames, BLOCK);
        return out;
    }
}
