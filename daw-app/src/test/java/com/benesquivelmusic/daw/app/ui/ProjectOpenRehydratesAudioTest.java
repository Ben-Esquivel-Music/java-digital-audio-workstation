package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.MissingAssetReason;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, "Goals — Tests", round-trip playback: import audio → save →
 * close → reopen through {@code ProjectLifecycleController.loadProjectFromPath}
 * → the clip's audio comes back sample-identical to what the import held,
 * the arrangement draws its waveform again — the same pixels as before the
 * save — and loading it did not make the project dirty (decision D13).
 *
 * <p>With the follow-up rule of decision D14: an open whose references were
 * all found says so in one SUCCESS; a file that is there but does not
 * decode is a row the run adds later, announced by exactly one follow-up
 * WARNING when the run ends — and an open whose every decode succeeds gets
 * none.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectOpenRehydratesAudioTest {

    @TempDir
    Path workspace;

    private OpenProjectFixture fixture;

    @BeforeEach
    void createTheController() throws Exception {
        fixture = new OpenProjectFixture(workspace);
    }

    @AfterEach
    void closeTheController() throws Exception {
        fixture.close();
    }

    @Test
    void anImportedClipReopensWithTheAudioItsImportHeldAndDrawsTheSameWaveformAsBeforeTheSave()
            throws Exception {
        // A 44.1 kHz file in a 48 kHz project: the import attached converted audio.
        Path source = OpenProjectFixture.wav(workspace.resolve("library").resolve("tone.wav"), 44_100, 4.0, 220.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Round Trip", source);
        AudioClip imported = saved.clips().getFirst();
        float[][] beforeSave = imported.getAudioData();
        Track savedTrack = saved.saved().getTracks().getFirst();
        int[] savedPixels = onFx(() -> OpenProjectFixture.arrangementPixels(savedTrack, ClipAudioState::unmanaged));

        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();

        AudioClip clip = fixture.liveClips().getFirst();
        Track liveTrack = onFx(() -> fixture.project.get().getTracks().getFirst());
        assertThat(clip.getAudioData()).as("the load restores the reference, not the samples").isNull();
        assertThat(onFx(() -> fixture.audio().stateOf(clip))).isEqualTo(ClipAudioState.LOADING);
        assertThat(onFx(() -> ClipOverlayRenderer.bodyFor(clip, fixture.audio().stateOf(clip))))
                .isEqualTo(ClipOverlayRenderer.ClipBody.LOADING_PLACEHOLDER);
        int[] loadingPixels = onFx(() -> OpenProjectFixture.arrangementPixels(liveTrack, fixture.audio()::stateOf));
        assertThat(Arrays.equals(loadingPixels, savedPixels))
                .as("non-vacuity: the snapshot tells the Loading… placeholder from the waveform").isFalse();

        fixture.releaseDecodesAndPulse();

        assertThat(Arrays.deepEquals(clip.getAudioData(), beforeSave))
                .as("the reopened clip holds exactly the samples the import held before the save").isTrue();
        assertThat(clip.getSourceRateMetadata()).as("converted at load, as at import").isNull();
        assertThat(onFx(() -> fixture.audio().stateOf(clip))).isEqualTo(ClipAudioState.LOADED);
        assertThat(onFx(() -> ClipOverlayRenderer.bodyFor(clip, fixture.audio().stateOf(clip))))
                .as("the renderer takes the waveform branch, not a placeholder")
                .isEqualTo(ClipOverlayRenderer.ClipBody.WAVEFORM);
        int[] reopenedPixels = onFx(() -> OpenProjectFixture.arrangementPixels(liveTrack, fixture.audio()::stateOf));
        assertThat(Arrays.equals(reopenedPixels, savedPixels))
                .as("the reopened arrangement draws the same pixels as before the save").isTrue();
        assertThat(onFx(() -> fixture.project.get().isDirty())).as("loading is not an edit").isFalse();
        assertThat(fixture.repaints).as("the drain repainted the arrangement").hasValue(1);

        fixture.pulse();
        assertThat(fixture.notifications()).as("one SUCCESS, and no follow-up when every decode succeeded")
                .singleElement().satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.SUCCESS);
                    assertThat(entry.message()).isEqualTo("Opened project: " + saved.directory().getFileName());
                    assertThat(entry.action()).as("nothing to review").isEmpty();
                });
        assertThat(fixture.presenter.presentations).as("nothing to report").isEmpty();
        assertThat(fixture.operationNames()).as("the loading operation left the strip").isEmpty();
    }

    @Test
    void aFileThatIsThereButDoesNotDecodeOpensCleanAndIsAnnouncedByOneFollowUpWhenTheRunEnds()
            throws Exception {
        Path good = OpenProjectFixture.wav(workspace.resolve("library").resolve("good.wav"), 48_000, 1.0, 330.0);
        Path broken = OpenProjectFixture.wav(workspace.resolve("library").resolve("broken.wav"), 48_000, 1.0, 440.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Broken", good, broken);
        Files.write(broken, new byte[]{'n', 'o', 't', ' ', 'a', ' ', 'w', 'a', 'v', 'e'});

        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        assertThat(fixture.notifications()).as("the file is there, so the open is clean").singleElement()
                .satisfies(entry -> assertThat(entry.level()).isEqualTo(NotificationLevel.SUCCESS));

        fixture.releaseDecodesAndPulse();

        List<NotificationEntry> entries = fixture.notifications();
        assertThat(entries).hasSize(2);
        NotificationEntry followUp = entries.get(1);
        assertThat(followUp.level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(followUp.message()).isEqualTo("1 asset could not be loaded as saved");
        assertThat(followUp.actionLabel()).contains("Review…");
        assertThat(fixture.presenter.presentations).as("a follow-up does not open the report by itself").isEmpty();

        runOnFx(() -> followUp.action().orElseThrow().run());
        assertThat(fixture.presenter.presentations).singleElement().satisfies(presentation ->
                assertThat(presentation.report().assets()).singleElement().satisfies(row -> {
                    assertThat(row.reference()).isEqualTo(new AssetReference.SingleFile(broken.toString()));
                    assertThat(row.reason()).isEqualTo(MissingAssetReason.UNREADABLE);
                }));
        List<AudioClip> clips = fixture.liveClips();
        assertThat(clips.get(0).getAudioData()).as("the good clip landed").isNotNull();
        assertThat(clips.get(1).getAudioData()).isNull();
        assertThat(onFx(() -> fixture.audio().stateOf(clips.get(1)))).isEqualTo(ClipAudioState.OFFLINE);

        fixture.pulse();
        fixture.pulse();
        assertThat(fixture.notifications()).as("exactly one follow-up").hasSize(2);
    }
}
